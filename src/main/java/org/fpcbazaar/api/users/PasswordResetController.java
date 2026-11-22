package org.fpcbazaar.api.users;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.fpcbazaar.api.notifications.MailProperties;
import org.fpcbazaar.api.notifications.Notifications;
import org.fpcbazaar.api.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {
  private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);
  private final UserRepository users;
  private final MongoTemplate mongo;
  private final PasswordEncoder encoder;
  private final Notifications notifications;
  private final MailProperties props;
  private final Clock clock;
  private final AuditService audit;
  private final SlidingWindowLimiter ipLimiter;
  private final SlidingWindowLimiter accountLimiter;
  private final SecureRandom random = new SecureRandom();

  public PasswordResetController(UserRepository users, MongoTemplate mongo, PasswordEncoder encoder,
      Notifications notifications, MailProperties props, Clock clock, AuditService audit) {
    this.users = users; this.mongo = mongo; this.encoder = encoder; this.notifications = notifications;
    this.props = props; this.clock = clock; this.audit = audit;
    ipLimiter = new SlidingWindowLimiter(20, Duration.ofMinutes(15), clock);
    accountLimiter = new SlidingWindowLimiter(3, Duration.ofMinutes(15), clock);
    if (props.resetTtl().compareTo(Duration.ofMinutes(5)) < 0 || props.resetTtl().compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalStateException("PASSWORD_RESET_TTL must be between 5m and 1h");
  }
  public record Forgot(@NotBlank @Size(max=120) String username) {}
  public record Reset(@NotBlank @Size(max=100) String token, @NotBlank @Size(max=200) String newPassword) {}

  @PostMapping("/forgot-password")
  public Map<String, Object> forgot(@Valid @RequestBody Forgot body, HttpServletRequest request) {
    limit(request);
    Map<String, Object> response = Map.of("ok", true, "message", "If an active email login exists, a password reset link will be sent.");
    String username = body.username().trim().toLowerCase(Locale.ROOT);
    if (!props.enabled() || !accountLimiter.tryAcquire(username) || !Notifications.isEmail(username)) return response;
    User u = users.findByUsername(username).filter(User::isActive).orElse(null);
    if (u == null) return response;
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String hash = hash(token);
    Instant expires = Instant.now(clock).plus(props.resetTtl());
    Query q = Query.query(Criteria.where("_id").is(u.getId()).and("active").is(true).and("tokenVersion").is(u.getTokenVersion()));
    var saved = mongo.updateFirst(q, new Update().set("resetTokenHash", hash).set("resetExpiresAt", expires)
        .set("resetTokenVersion", u.getTokenVersion()), User.class);
    if (saved.getMatchedCount() == 0) return response;
    try {
      if (!notifications.resetLink(u, token, expires)) clearFailedToken(u.getId(), hash);
    } catch (RuntimeException e) {
      clearFailedToken(u.getId(), hash);
      log.error("Password reset email could not be queued");
    }
    return response;
  }

  @PostMapping("/reset-password")
  public Map<String, Object> reset(@Valid @RequestBody Reset body, HttpServletRequest request) {
    limit(request);
    String problem = Passwords.problem(body.newPassword(), null);
    if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem, Map.of("newPassword", problem));
    Query q = Query.query(Criteria.where("resetTokenHash").is(hash(body.token())).and("resetExpiresAt").gt(Instant.now(clock))
        .and("active").is(true));
    User u = mongo.findOne(q, User.class);
    if (u == null) throw invalidToken();
    problem = Passwords.problem(body.newPassword(), u.getUsername());
    if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem, Map.of("newPassword", problem));
    String encoded = encoder.encode(body.newPassword());
    q = Query.query(Criteria.where("_id").is(u.getId()).and("resetTokenHash").is(hash(body.token()))
        .and("resetExpiresAt").gt(Instant.now(clock)).and("active").is(true)
        .and("tokenVersion").is(u.getTokenVersion()).and("resetTokenVersion").is(u.getTokenVersion()));
    User saved = mongo.findAndModify(q, new Update().set("passwordHash", encoded).set("mustChangePassword", false)
        .inc("tokenVersion", 1).unset("resetTokenHash").unset("resetExpiresAt").unset("resetTokenVersion"),
        FindAndModifyOptions.options().returnNew(true), User.class);
    if (saved == null) throw invalidToken();
    audit.log(saved.getId(), saved.getName(), saved.getRole().code(), "password_reset_completed", "user", saved.getId(), "Self-service reset");
    return Map.of("ok", true, "message", "Password changed. Please sign in with your new password.");
  }

  private void clearFailedToken(String id, String hash) {
    mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("resetTokenHash").is(hash)),
        new Update().unset("resetTokenHash").unset("resetExpiresAt").unset("resetTokenVersion"), User.class);
  }
  private void limit(HttpServletRequest request) {
    if (!ipLimiter.tryAcquire(ClientIp.of(request))) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again after 15 minutes.");
  }
  private static ApiException invalidToken() { return ApiException.badRequest("This reset link is invalid or expired. Request a new one."); }
  public static String hash(String token) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
