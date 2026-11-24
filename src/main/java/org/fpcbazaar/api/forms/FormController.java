package org.fpcbazaar.api.forms;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.enquiries.Enquiry;
import org.fpcbazaar.api.enquiries.EnquiryRepository;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationService;
import org.fpcbazaar.api.users.SelfRegistration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * {@code POST /api/forms/{formKey}}: every public form of the website.
 * JSON when there is no file, multipart/form-data when documents are attached (docs/ADMIN_API.md).
 */
@RestController
public class FormController {
  private final org.fpcbazaar.api.notifications.Notifications notifications;
  private final SelfRegistration selfRegistration;
  private final FormValidator validator;
  private final RegistrationService registrations;
  private final EnquiryRepository enquiries;
  private final SlidingWindowLimiter limiter;
  private final Clock clock;

  public FormController(FormValidator validator, RegistrationService registrations, EnquiryRepository enquiries,
      AppProperties props, Clock clock, org.fpcbazaar.api.notifications.Notifications notifications,
      SelfRegistration selfRegistration) {
    this.selfRegistration = selfRegistration;
    this.notifications = notifications;
    this.validator = validator;
    this.registrations = registrations;
    this.enquiries = enquiries;
    int perMinute = props.rateLimit() == null ? 10 : props.rateLimit().formsPerMinute();
    this.limiter = new SlidingWindowLimiter(perMinute, Duration.ofMinutes(1), clock);
    this.clock = clock;
  }

  @PostMapping(path = "/api/forms/{formKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> json(@PathVariable String formKey, @RequestBody Map<String, Object> body,
      HttpServletRequest req) {
    Map<String, String> text = new LinkedHashMap<>();
    body.forEach((k, v) -> {
      if (v == null) return;
      if (v instanceof Map || v instanceof List) throw ApiException.badRequest("The request could not be read.");
      text.put(k, String.valueOf(v));
    });
    return handle(formKey, text, Map.of(), req);
  }

  @PostMapping(path = "/api/forms/{formKey}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> multipart(@PathVariable String formKey, MultipartHttpServletRequest req) {
    Map<String, String> text = new LinkedHashMap<>();
    req.getParameterMap().forEach((k, v) -> {
      if (v.length > 1) throw ApiException.badRequest("Field '" + k + "' was sent twice.");
      if (v.length == 1) text.put(k, v[0]);
    });
    Map<String, MultipartFile> files = new LinkedHashMap<>();
    req.getMultiFileMap().forEach((k, list) -> {
      if (list.size() > 1) throw ApiException.badRequest("Only one file per field.");
      if (!list.isEmpty()) files.put(k, list.get(0));
    });
    return handle(formKey, text, files, req);
  }

  private ResponseEntity<Map<String, Object>> handle(String formKey, Map<String, String> text, Map<String, MultipartFile> files,
      HttpServletRequest req) {
    List<FormField> schema = FormSchemas.get(formKey);
    if (schema == null) throw ApiException.notFound("Unknown form.");
    if (!limiter.tryAcquire(ClientIp.of(req)))
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many submissions. Please wait a minute and try again.");

    // Honeypot: real visitors never fill this hidden field. Answer "ok" so bots learn nothing.
    String honeypot = text.get("website");
    if (honeypot != null && !honeypot.isBlank()) return ResponseEntity.ok(Map.of("ok", true));

    if (!"true".equalsIgnoreCase(String.valueOf(text.get("consent")).trim()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Please accept the consent checkbox.", Map.of("consent", "Consent is required."));

    // Optional login for FPO / vendor forms: the password is taken out here and never stored with the form.
    String password = text.remove("password");
    String passwordConfirm = text.remove("passwordConfirm");
    FormValidator.Result form = validator.validate(schema, text, files);
    String lang = "hi".equals(text.get("lang")) ? "hi" : "en";
    String page = text.get("page");

    if (FormSchemas.isRegistration(formKey)) {
      SelfRegistration.Outcome login = selfRegistration.precheck(formKey, password, passwordConfirm, form.values().get("email"));
      Registration r = registrations.create(formKey, form, lang, page);
      if (login == SelfRegistration.Outcome.CONFIRM_EMAIL) login = selfRegistration.create(r, password);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("ok", true);
      out.put("id", r.getId());
      out.put("status", r.getStatus());
      if (login != SelfRegistration.Outcome.NOT_REQUESTED) out.put("login", login == SelfRegistration.Outcome.CONFIRM_EMAIL ? "confirm_email" : "after_verification");
      return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }
    Enquiry e = new Enquiry();
    e.setFormKey(formKey);
    e.setData(form.values());
    e.setLang(lang);
    e.setPage(page == null ? null : page.substring(0, Math.min(page.length(), 200)));
    e.setCreatedAt(Instant.now(clock));
    enquiries.save(e);
    notifications.received(e.getData().get("email"), e.getData().get("name"), formKey, e.getId(), lang);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
  }
}
