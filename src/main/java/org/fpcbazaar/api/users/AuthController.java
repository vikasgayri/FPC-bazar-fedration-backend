package org.fpcbazaar.api.users;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.security.Auth;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.JwtService;
import org.fpcbazaar.api.security.Passwords;
import org.fpcbazaar.api.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  /** Used when the username does not exist, so a wrong username takes as long as a wrong password. */
  private final String dummyHash;

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  /** Per username: protects one account. */
  private final SlidingWindowLimiter userFailures;
  /** Per IP: 4x higher, because a whole office (many staff) can share one IP. */
  private final SlidingWindowLimiter ipFailures;
  private final AuditService audit;
  private final Clock clock;
  private final UserWrites writes;
  private final LoginOtpService otp;
  /** Per IP: code checks and "send again" (the code itself is also limited per sign-in). */
  private final SlidingWindowLimiter otpIp;

  public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, AppProperties props, Clock clock,
      AuditService audit, UserWrites writes, LoginOtpService otp) {
    this.users = users;
    this.otp = otp;
    this.otpIp = new SlidingWindowLimiter(40, props.rateLimit().loginWindow(), clock);
    this.writes = writes;
    this.audit = audit;
    this.clock = clock;
    this.encoder = encoder;
    this.jwt = jwt;
    this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    int max = props.rateLimit().loginFailuresPerWindow();
    this.userFailures = new SlidingWindowLimiter(max, props.rateLimit().loginWindow(), clock);
    this.ipFailures = new SlidingWindowLimiter(max * 4, props.rateLimit().loginWindow(), clock);
  }

  public record LoginRequest(@NotBlank @Size(max = 120) String username, @NotBlank @Size(max = 128) String password) {}

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
    String username = body.username().trim().toLowerCase(java.util.Locale.ROOT);
    String ipKey = "ip:" + ClientIp.of(req);
    String userKey = "user:" + username;
    // Reserve an attempt BEFORE checking the password, so many requests sent at the same moment
    // cannot all slip past the limit. A successful sign-in gives the attempt back.
    reserve(userKey, ipKey);

    User u = users.findByUsername(username).orElse(null);
    boolean ok = encoder.matches(body.password(), u == null ? dummyHash : u.getPasswordHash()) && u != null && u.isActive();
    if (!ok) throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
    userFailures.reset(userKey);
    ipFailures.release(ipKey);
    // Self-registered logins open only after the emailed link is used (the password was right, so saying so leaks nothing).
    if (u.isEmailUnconfirmed())
      throw new ApiException(HttpStatus.FORBIDDEN, "Please confirm your email first. Open the link we emailed to you, or ask for a new link.",
          Map.of("email", "email_unconfirmed"));
    // Second step: the token is given only after the code emailed to the user is entered (POST /verify-otp).
    if (otp.enabled()) return otp.start(u);
    return signedIn(u, "password");
  }

  public record OtpRequest(@NotBlank @Size(max = 100) String challenge, @Size(max = 20) String code) {}

  /** Second sign-in step: the 6-digit code from the email. Returns the same session as a password-only login. */
  @PostMapping("/verify-otp")
  public Map<String, Object> verifyOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest req) {
    if (!otpIp.tryAcquire("ip:" + ClientIp.of(req))) throw tooMany();
    return signedIn(otp.verify(body.challenge(), body.code()), "password + email code");
  }

  /** Emails a new code for the same sign-in (after a short wait). */
  @PostMapping("/resend-otp")
  public Map<String, Object> resendOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest req) {
    if (!otpIp.tryAcquire("ip:" + ClientIp.of(req))) throw tooMany();
    return otp.resend(body.challenge());
  }

  private Map<String, Object> signedIn(User u, String how) {
    writes.touchLogin(u.getId(), Instant.now(clock)); // only lastLoginAt; never overwrites a switch-off made meanwhile
    audit.log(u.getId(), u.getName(), u.getRole().code(), "login", "user", u.getId(), u.getUsername() + " (" + how + ")");
    return session(u);
  }

  private void reserve(String userKey, String ipKey) {
    if (!userFailures.tryAcquire(userKey)) throw tooMany();
    if (!ipFailures.tryAcquire(ipKey)) {
      userFailures.release(userKey);
      throw tooMany();
    }
  }

  private static ApiException tooMany() {
    return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong attempts. Please try again after 15 minutes.");
  }

  private Map<String, Object> session(User u) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("token", jwt.issue(u));
    out.put("role", u.getRole().code());
    out.put("name", u.getName());
    if (u.getState() != null) out.put("state", u.getState());
    if (u.getRecordId() != null) out.put("recordId", u.getRecordId());
    out.put("mustChangePassword", u.isMustChangePassword());
    return out;
  }

  public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}

  /** Changes the signed-in user's password. Old tokens stop working; a fresh token is returned. */
  @PostMapping("/change-password")
  public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
    CurrentUser cu = Auth.current();
    User u = users.findById(cu.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in again."));
    String userKey = "user:" + u.getUsername();
    if (!userFailures.tryAcquire(userKey)) throw tooMany();
    if (!encoder.matches(body.currentPassword(), u.getPasswordHash()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is wrong.", Map.of("currentPassword", "Current password is wrong."));
    userFailures.reset(userKey);
    String problem = Passwords.problem(body.newPassword(), u.getUsername());
    if (problem == null && encoder.matches(body.newPassword(), u.getPasswordHash())) problem = "Choose a new password.";
    if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem, Map.of("newPassword", problem));
    User saved = writes.changeOwnPassword(u.getId(), u.getTokenVersion(), encoder.encode(body.newPassword()));
    if (saved == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
    audit.log(cu, "password_changed", "user", saved.getId(), saved.getUsername());
    return session(saved);
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    CurrentUser cu = Auth.current();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("username", cu.username());
    out.put("role", cu.role().code());
    out.put("name", cu.name());
    if (cu.state() != null) out.put("state", cu.state());
    if (cu.recordId() != null) out.put("recordId", cu.recordId());
    out.put("mustChangePassword", users.findById(cu.id()).map(User::isMustChangePassword).orElse(false));
    return out;
  }
}
