package org.fpcbazaar.api.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.fpcbazaar.api.notifications.MailProperties;
import org.fpcbazaar.api.notifications.Notifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Email sign-in code (OTP), the second step of every sign-in.
 * Password OK -> {@link #start} emails a 6-digit code -> {@link #verify} returns the user -> the controller issues the token.
 * Only one pending sign-in per login: a new one cancels the old one.
 */
@Service
public class LoginOtpService {
  private static final Logger log = LoggerFactory.getLogger(LoginOtpService.class);
  private static final Pattern CODE = Pattern.compile("^\\d{6}$");
  private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{20,100}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final LoginOtpProperties props;
  private final MailProperties mail;
  private final MongoTemplate mongo;
  private final UserRepository users;
  private final Notifications notifications;
  private final Clock clock;
  private final SlidingWindowLimiter issues;

  public LoginOtpService(LoginOtpProperties props, MailProperties mail, MongoTemplate mongo, UserRepository users,
      Notifications notifications, Clock clock) {
    this.props = props;
    this.mail = mail;
    this.mongo = mongo;
    this.users = users;
    this.notifications = notifications;
    this.clock = clock;
    this.issues = new SlidingWindowLimiter(props.maxIssuesPerWindow(), props.issueWindow(), clock);
  }

  public boolean enabled() { return props.enabled(); }

  @EventListener(ApplicationReadyEvent.class)
  void warnIfUndeliverable() {
    if (!props.enabled()) log.warn("LOGIN_OTP=false: sign-in works with the password only. Never use this on the live server.");
    else if (!mail.enabled()) log.error("LOGIN_OTP is on but MAIL_ENABLED=false: sign-in codes cannot be emailed, so nobody can sign in.");
    else if (mail.sandbox()) log.warn("LOGIN_OTP is on and MAIL_SANDBOX=true: sign-in codes are not delivered, so nobody can sign in.");
  }

  /** Called after the password was accepted. Emails a code and returns what the browser needs for the code step. */
  public Map<String, Object> start(User u) {
    if (!Notifications.isEmail(u.getUsername()))
      throw new ApiException(HttpStatus.FORBIDDEN, "This login has no email address, so the sign-in code cannot be sent. "
          + "Ask the admin to create a login with your email.", Map.of("username", "no_email"));
    if (!mail.enabled()) {
      log.error("Sign-in code for user {} not sent: MAIL_ENABLED=false", u.getId());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sign-in is not available right now because email is not set up. Please contact the federation.");
    }
    if (!issues.tryAcquire(u.getId()))
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in codes were sent. Please try again after 15 minutes.");

    Instant now = Instant.now(clock);
    mongo.remove(Query.query(Criteria.where("userId").is(u.getId())), LoginOtp.class);
    LoginOtp otp = new LoginOtp();
    otp.id = randomId();
    otp.userId = u.getId();
    otp.tokenVersion = u.getTokenVersion();
    otp.sends = 1;
    otp.lastSentAt = now;
    otp.expiresAt = now.plus(props.ttl());
    otp.purgeAt = otp.expiresAt.plus(Duration.ofHours(1));
    String code = newCode();
    otp.codeHash = hash(otp.id, code);
    mongo.insert(otp);
    if (!notifications.loginCode(u, code, otp.expiresAt, props.ttl())) {
      mongo.remove(Query.query(Criteria.where("_id").is(otp.id)), LoginOtp.class);
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The sign-in code could not be sent. Please try again in a few minutes.");
    }
    return challenge(otp, u.getUsername(), now);
  }

  /** Checks the code. Returns the user (still active, password unchanged) or throws. The code works once. */
  public User verify(String challenge, String code) {
    if (challenge == null || !CHALLENGE.matcher(challenge).matches()) throw expired();
    String c = code == null ? "" : code.replaceAll("\\s", "");
    if (!CODE.matcher(c).matches())
      throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the 6-digit code from the email.", Map.of("code", "Enter the 6-digit code."));
    Instant now = Instant.now(clock);
    // Count the attempt first (atomic), so parallel guesses cannot exceed the limit.
    LoginOtp otp = mongo.findAndModify(Query.query(Criteria.where("_id").is(challenge).and("expiresAt").gt(now)
            .and("attempts").lt(props.maxAttempts())), new Update().inc("attempts", 1),
        FindAndModifyOptions.options().returnNew(true), LoginOtp.class);
    if (otp == null) {
      mongo.remove(Query.query(Criteria.where("_id").is(challenge)), LoginOtp.class);
      throw expired();
    }
    if (!MessageDigest.isEqual(hash(challenge, c).getBytes(StandardCharsets.US_ASCII), otp.codeHash.getBytes(StandardCharsets.US_ASCII))) {
      int left = props.maxAttempts() - otp.attempts;
      if (left <= 0) {
        mongo.remove(Query.query(Criteria.where("_id").is(challenge)), LoginOtp.class);
        throw new ApiException(HttpStatus.BAD_REQUEST, "Too many wrong codes. Please sign in again.", Map.of("code", "otp_expired"));
      }
      throw new ApiException(HttpStatus.BAD_REQUEST, "Wrong code. " + left + (left == 1 ? " try" : " tries") + " left.",
          Map.of("code", "otp_wrong"));
    }
    // Single use: only the request that removes the challenge may sign in.
    if (mongo.remove(Query.query(Criteria.where("_id").is(challenge).and("codeHash").is(otp.codeHash)), LoginOtp.class).getDeletedCount() != 1)
      throw expired();
    User u = users.findById(otp.userId).orElse(null);
    if (u == null || !u.isActive() || u.isEmailUnconfirmed() || u.getTokenVersion() != otp.tokenVersion) throw expired();
    return u;
  }

  /** Sends a new code for the same sign-in (after a short wait). The old code stops working. */
  public Map<String, Object> resend(String challenge) {
    if (challenge == null || !CHALLENGE.matcher(challenge).matches()) throw expired();
    Instant now = Instant.now(clock);
    String code = newCode();
    Instant expires = now.plus(props.ttl());
    LoginOtp otp = mongo.findAndModify(Query.query(Criteria.where("_id").is(challenge).and("expiresAt").gt(now)
            .and("sends").lt(props.maxSends()).and("lastSentAt").lte(now.minus(props.resendAfter()))),
        new Update().set("codeHash", hash(challenge, code)).set("attempts", 0).set("lastSentAt", now).set("expiresAt", expires)
            .set("purgeAt", expires.plus(Duration.ofHours(1))).inc("sends", 1),
        FindAndModifyOptions.options().returnNew(true), LoginOtp.class);
    if (otp == null) {
      LoginOtp current = mongo.findById(challenge, LoginOtp.class);
      if (current == null || !current.expiresAt.isAfter(now)) throw expired();
      if (current.sends >= props.maxSends())
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "No more codes for this sign-in. Please sign in again.", Map.of("code", "otp_expired"));
      long wait = Math.max(1, Duration.between(now, current.lastSentAt.plus(props.resendAfter())).toSeconds());
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait " + wait + " seconds before asking for a new code.");
    }
    User u = users.findById(otp.userId).orElse(null);
    if (u == null || !u.isActive() || u.getTokenVersion() != otp.tokenVersion) {
      mongo.remove(Query.query(Criteria.where("_id").is(challenge)), LoginOtp.class);
      throw expired();
    }
    if (!notifications.loginCode(u, code, expires, props.ttl()))
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The sign-in code could not be sent. Please try again in a few minutes.");
    return challenge(otp, u.getUsername(), now);
  }

  /** Removes pending sign-ins of a login (used when a login is deleted). */
  public void cancelFor(String userId) {
    mongo.remove(Query.query(Criteria.where("userId").is(userId)), LoginOtp.class);
  }

  private Map<String, Object> challenge(LoginOtp otp, String email, Instant now) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("otpRequired", true);
    out.put("challenge", otp.id);
    out.put("email", mask(email));
    out.put("expiresInSeconds", Duration.between(now, otp.expiresAt).toSeconds());
    out.put("resendInSeconds", props.resendAfter().toSeconds());
    return out;
  }

  private static ApiException expired() {
    return new ApiException(HttpStatus.BAD_REQUEST, "This code has expired. Please sign in again.", Map.of("code", "otp_expired"));
  }

  /** a****z@example.org: enough for the user to recognise the inbox, not enough to learn the address. */
  static String mask(String email) {
    int at = email.indexOf('@');
    if (at <= 0) return "your email";
    String local = email.substring(0, at);
    String shown = local.length() <= 2 ? local.substring(0, 1) + "*" : local.charAt(0) + "*".repeat(Math.min(6, local.length() - 2)) + local.charAt(local.length() - 1);
    return shown + email.substring(at);
  }

  private static String newCode() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }

  private static String randomId() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** Bound to the challenge id, so a code hash is useless for any other sign-in. */
  private static String hash(String challenge, String code) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(d.digest((challenge + ":" + code).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
