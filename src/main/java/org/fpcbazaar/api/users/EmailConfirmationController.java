package org.fpcbazaar.api.users;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Email confirmation for self-registered logins: the emailed link's page, the confirm call and "send the link again". */
@RestController
public class EmailConfirmationController {
  private final SelfRegistration self;
  private final SlidingWindowLimiter limiter;

  public EmailConfirmationController(SelfRegistration self, Clock clock) {
    this.self = self;
    this.limiter = new SlidingWindowLimiter(20, Duration.ofMinutes(15), clock);
  }

  public record Confirm(@NotBlank @Size(max = 100) String token) {}

  public record Resend(@NotBlank @Size(max = 120) String username) {}

  @GetMapping("/confirm-email")
  public ResponseEntity<Resource> page() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
        .body(new ClassPathResource("static/confirm-email.html"));
  }

  @PostMapping("/api/auth/confirm-email")
  public Map<String, Object> confirm(@Valid @RequestBody Confirm body, HttpServletRequest req) {
    limit(req);
    if (!self.confirm(body.token())) throw ApiException.badRequest("This link is invalid or expired. Ask for a new link from the sign-in page.");
    return Map.of("ok", true, "message", "Email confirmed. You can now sign in with your email and password.");
  }

  @PostMapping("/api/auth/resend-confirmation")
  public Map<String, Object> resend(@Valid @RequestBody Resend body, HttpServletRequest req) {
    limit(req);
    self.resend(body.username());
    return Map.of("ok", true, "message", "If this login is waiting for confirmation, a new link has been sent to its email.");
  }

  private void limit(HttpServletRequest req) {
    if (!limiter.tryAcquire(ClientIp.of(req)))
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again after 15 minutes.");
  }
}
