package org.fpcbazaar.api.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.registrations.RegistrationSubmittedEvent;
import org.fpcbazaar.api.registrations.RegistrationDecidedEvent;
import org.fpcbazaar.api.registrations.Status;
import org.fpcbazaar.api.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class Notifications {
  private static final Logger log = LoggerFactory.getLogger(Notifications.class);
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private final MailQueue queue;
  private final RegistrationRepository registrations;
  private final MailProperties props;
  private final Clock clock;
  private final String resetBaseUrl;

  public Notifications(MailQueue queue, RegistrationRepository registrations, MailProperties props, Clock clock, org.fpcbazaar.api.config.AppProperties app) {
    this.resetBaseUrl = app.publicBaseUrl().replaceAll("/+$", "");
    this.queue = queue; this.registrations = registrations; this.props = props; this.clock = clock;
  }
  public static boolean isEmail(String value) {
    return value != null && value.length() <= 254 && EMAIL.matcher(value).matches();
  }

  @EventListener
  public void submitted(RegistrationSubmittedEvent event) {
    safely(() -> registrations.findById(event.registrationId()).ifPresent(r -> received(r.getData().get("email"),
        r.getName(), r.getKind(), r.getId(), r.getLang())));
  }

  @EventListener
  public void decided(RegistrationDecidedEvent event) {
    if (event.to() == Status.PENDING) return;
    safely(() -> registrations.findById(event.registrationId()).ifPresent(r -> {
      boolean hi = "hi".equals(r.getLang());
      boolean ok = event.to() == Status.VERIFIED;
      String what = label(r.getKind(), hi);
      String message = hi ? "आपके " + what + " की स्थिति: " + (ok ? "स्वीकृत (Verified)" : "अस्वीकृत (Rejected)")
          : "Your " + what + " has been " + (ok ? "verified" : "rejected") + ".";
      if (!ok) message += (hi ? "\nकारण: " : "\nReason: ") + event.reason();
      String subject = hi ? "FPC Bazaar — आवेदन " + (ok ? "स्वीकृत" : "अस्वीकृत")
          : "FPC Bazaar — Application " + (ok ? "verified" : "rejected");
      send("registration_" + event.to().code(), r.getData().get("email"), subject,
          greeting(r.getName()) + message + "\n" + (hi ? "संदर्भ संख्या: " : "Reference: ") + r.getId(), null);
    }));
  }

  /**
   * Acknowledgement for a registration or an enquiry form. {@code formKey} is the form's key (fpo, vendor, contact, quote...);
   * registrations get "application received", enquiries get "we received your request".
   */
  public void received(String email, String name, String formKey, String id, String lang) {
    boolean hi = "hi".equals(lang);
    boolean registration = REGISTRATIONS.contains(formKey);
    String what = label(formKey, hi);
    String subject = registration ? (hi ? "FPC Bazaar — आवेदन प्राप्त हुआ" : "FPC Bazaar — Application received")
        : (hi ? "FPC Bazaar — आपका संदेश मिल गया" : "FPC Bazaar — We received your request");
    String body = registration
        ? (hi ? "हमें आपका " + what + " मिल गया है। हमारी टीम इसकी जाँच करेगी।" : "We have received your " + what + ". Our team will review it.")
        : (hi ? "हमें आपका " + what + " मिल गया है। हमारी टीम जल्द आपसे संपर्क करेगी।"
            : "We have received your " + what + ". Our team will contact you soon.");
    safely(() -> send(registration ? "application_received" : "enquiry_received", email, subject,
        greeting(name) + body + "\n" + (hi ? "संदर्भ संख्या: " : "Reference: ") + id, null));
  }

  private static final java.util.Set<String> REGISTRATIONS = java.util.Set.of("fpo", "vendor", "ngo", "projecthead", "member", "job");
  private static final java.util.Map<String, String[]> LABELS = java.util.Map.ofEntries(
      java.util.Map.entry("fpo", new String[] {"FPO registration", "FPO पंजीकरण आवेदन"}),
      java.util.Map.entry("vendor", new String[] {"company / vendor registration", "कंपनी / वेंडर पंजीकरण आवेदन"}),
      java.util.Map.entry("ngo", new String[] {"NGO registration", "NGO पंजीकरण आवेदन"}),
      java.util.Map.entry("projecthead", new String[] {"Project Head application", "प्रोजेक्ट हेड आवेदन"}),
      java.util.Map.entry("member", new String[] {"membership application", "सदस्यता आवेदन"}),
      java.util.Map.entry("job", new String[] {"job application", "नौकरी आवेदन"}),
      java.util.Map.entry("contact", new String[] {"message", "संदेश"}),
      java.util.Map.entry("register", new String[] {"registration request", "पंजीकरण अनुरोध"}),
      java.util.Map.entry("eauto", new String[] {"E-Auto application", "ई-ऑटो आवेदन"}),
      java.util.Map.entry("amul", new String[] {"parlour and cafe enquiry", "पार्लर और कैफे पूछताछ"}),
      java.util.Map.entry("investor", new String[] {"investor and partner enquiry", "निवेशक और साझेदार पूछताछ"}),
      java.util.Map.entry("product", new String[] {"product enquiry", "उत्पाद पूछताछ"}),
      java.util.Map.entry("quote", new String[] {"quote request", "कोटेशन अनुरोध"}),
      java.util.Map.entry("supplier", new String[] {"supplier listing request", "सप्लायर लिस्टिंग अनुरोध"}),
      java.util.Map.entry("sourcing", new String[] {"sourcing request", "सोर्सिंग अनुरोध"}),
      java.util.Map.entry("connect", new String[] {"Business Connect request", "बिज़नेस कनेक्ट अनुरोध"}));

  static String label(String key, boolean hi) {
    String[] l = LABELS.get(key);
    if (l == null) return hi ? "अनुरोध" : "request";
    return hi ? l[1] : l[0];
  }

  public void account(User user, String temporaryPassword, boolean reset) {
    safely(() -> send(reset ? "account_reset" : "account_created", user.getUsername(),
        reset ? "FPC Bazaar — Password reset" : "FPC Bazaar — Your login is ready",
        greeting(user.getName()) + "Username: " + user.getUsername() + "\nOne-time password: " + temporaryPassword
            + "\nSign in: " + loginUrl() + "\nChange this password immediately after signing in.",
        Instant.now(clock).plusSeconds(86400)));
  }

  /** Reset requests need to know if queuing failed; the controller still returns a generic public response. */
  public boolean resetLink(User user, String token, Instant expiresAt) {
    return queue.enqueue("password_reset_link", new MailPayload(user.getUsername(), "FPC Bazaar — Reset your password",
        greeting(user.getName()) + "To reset your password, open:\n" + resetBaseUrl
        + "/reset-password#token=" + token + "\nThis link expires in " + props.resetTtl().toMinutes()
        + " minutes and can be used once. If you did not request this, ignore this email."), expiresAt);
  }

  private void send(String kind, String to, String subject, String text, Instant expires) {
    if (!isEmail(to)) return;
    queue.enqueue(kind, new MailPayload(to.toLowerCase(Locale.ROOT), subject, text), expires);
  }
  /** Link that opens a self-registered login (Hindi when the form was filled in Hindi). */
  public void confirmEmail(User user, String token, String lang, Instant expiresAt) {
    boolean hi = "hi".equals(lang);
    String link = resetBaseUrl + "/confirm-email#token=" + token;
    String text = greeting(user.getName()) + (hi
        ? "आपका लॉगिन बन गया है। इसे चालू करने के लिए यह लिंक खोलें:\n" + link
            + "\nयह लिंक 48 घंटे तक एक बार काम करेगा। उसके बाद इस ईमेल और अपने पासवर्ड से साइन इन करें: " + loginUrl()
            + "\nआपका आवेदन फेडरेशन की जाँच तक Pending रहेगा। अगर यह आपने नहीं किया है, तो इस ईमेल को अनदेखा करें।"
        : "Your login has been created. To switch it on, open this link:\n" + link
            + "\nThe link works once within 48 hours. Then sign in with this email and your password: " + loginUrl()
            + "\nYour application stays Pending until the Federation verifies it. If you did not do this, ignore this email.");
    queue.enqueue("email_confirmation", new MailPayload(user.getUsername(),
        hi ? "FPC Bazaar — अपना ईमेल पक्का करें" : "FPC Bazaar — Confirm your email", text), expiresAt);
  }

  /**
   * Sign-in code (second step of every sign-in). Returns false when it could not be queued.
   * The queue is asked to send at once: the user is waiting on the code screen.
   */
  public boolean loginCode(User user, String code, Instant expiresAt, java.time.Duration ttl) {
    long minutes = Math.max(1, ttl.toMinutes());
    String text = greeting(user.getName()) + "Your FPC Bazaar sign-in code is:\n\n    " + code
        + "\n\nIt works once, within " + minutes + " minutes. Never share this code with anyone; the federation will never ask for it."
        + "\nIf you did not try to sign in, someone may know your password: change it at " + loginUrl()
        + "\n\nआपका साइन-इन कोड: " + code + " (" + minutes + " मिनट तक मान्य)। यह कोड किसी को न बताएं।";
    boolean queued;
    try {
      queued = queue.enqueue("login_code", new MailPayload(user.getUsername(), "FPC Bazaar — Your sign-in code", text), expiresAt);
    } catch (RuntimeException e) {
      log.error("Sign-in code could not be queued");
      return false;
    }
    if (queued) queue.kick();
    return queued;
  }

  /** Admin panel sign-in page of the website (FRONTEND_URL + /admin). */
  String loginUrl() { return props.frontendUrl().replaceAll("/+$", "") + "/admin"; }

  private static String greeting(String name) { return "Namaste " + (name == null ? "" : name) + ",\n\n"; }
  private static void safely(Runnable action) {
    try { action.run(); }
    catch (RuntimeException e) { log.error("Notification could not be queued; review mail status and retry the account notification if needed"); }
  }
}
