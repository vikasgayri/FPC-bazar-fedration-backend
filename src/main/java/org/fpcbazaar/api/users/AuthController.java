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

  public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, AppProperties props, Clock clock) {
    this.users = users;
    this.encoder = encoder;
    this.jwt = jwt;
    this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    int max = props.rateLimit().loginFailuresPerWindow();
    this.userFailures = new SlidingWindowLimiter(max, props.rateLimit().loginWindow(), clock);
    this.ipFailures = new SlidingWindowLimiter(max * 4, props.rateLimit().loginWindow(), clock);
  }

  public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
    String username = body.username().trim().toLowerCase();
    String ipKey = "ip:" + ClientIp.of(req);
    String userKey = "user:" + username;
    if (ipFailures.isBlocked(ipKey) || userFailures.isBlocked(userKey))
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong attempts. Please try again after 15 minutes.");

    User u = users.findByUsername(username).orElse(null);
    boolean ok = encoder.matches(body.password(), u == null ? dummyHash : u.getPasswordHash()) && u != null && u.isActive();
    if (!ok) {
      ipFailures.tryAcquire(ipKey);
      userFailures.tryAcquire(userKey);
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
    }
    userFailures.reset(userKey);
    u.setLastLoginAt(Instant.now());
    users.save(u);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("token", jwt.issue(u));
    out.put("role", u.getRole().code());
    out.put("name", u.getName());
    if (u.getState() != null) out.put("state", u.getState());
    if (u.getRecordId() != null) out.put("recordId", u.getRecordId());
    return out;
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
    return out;
  }
}
