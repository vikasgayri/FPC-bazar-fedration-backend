package org.fpcbazaar.api.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.registrations.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Track your application": anyone with the reference number AND the mobile number used in the form can see the status
 * (and the reason, if rejected). Both must match; a wrong pair gets the same answer as an unknown reference, and one
 * internet connection gets only 20 wrong tries in 15 minutes, so references cannot be guessed. Successful look-ups do not
 * count: a whole office (one IP) can keep checking its own applications.
 */
@RestController
public class TrackController {
  private static final Pattern REFERENCE = Pattern.compile("^[a-fA-F0-9]{24}$");
  private static final Pattern PREFIX = Pattern.compile("^(\\+91|91|0)(?=\\d{10}$)");
  private final RegistrationRepository repo;
  static final int WRONG_TRIES = 20;
  private final SlidingWindowLimiter limiter;

  public TrackController(RegistrationRepository repo, Clock clock) {
    this.repo = repo;
    this.limiter = new SlidingWindowLimiter(WRONG_TRIES, Duration.ofMinutes(15), clock);
  }

  public record TrackRequest(@NotBlank @Size(max = 40) String reference, @NotBlank @Size(max = 20) String phone) {}

  @PostMapping("/api/public/track")
  public Map<String, Object> track(@Valid @RequestBody TrackRequest body, HttpServletRequest req) {
    String ip = ClientIp.of(req);
    // Reserve first (parallel guesses cannot slip past the limit); a successful look-up gives it back.
    if (!limiter.tryAcquire(ip))
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many tries. Please try again after 15 minutes.");
    String ref = body.reference().trim();
    String phone = normalise(body.phone());
    Registration r = REFERENCE.matcher(ref).matches() ? repo.findById(ref.toLowerCase()).orElse(null) : null;
    if (r == null || phone.isEmpty() || !phone.equals(normalise(r.getData().get("phone"))))
      throw ApiException.notFound("No application found with this reference and mobile number. Check both and try again.");
    limiter.release(ip);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reference", r.getId());
    out.put("kind", r.getKind());
    out.put("name", r.getName());
    out.put("status", r.getStatus());
    if (r.getStatus() == Status.REJECTED) out.put("reason", r.getReason());
    out.put("submittedAt", r.getCreatedAt());
    if (r.getStatus() != Status.PENDING) out.put("decidedAt", r.getDecidedAt());
    return out;
  }

  static String normalise(String raw) {
    if (raw == null) return "";
    return PREFIX.matcher(raw.replaceAll("[\\s-]", "")).replaceFirst("");
  }
}
