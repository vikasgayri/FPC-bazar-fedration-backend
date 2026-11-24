package org.fpcbazaar.api.users;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.notifications.MailProperties;
import org.fpcbazaar.api.notifications.Notifications;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.security.Passwords;
import org.fpcbazaar.api.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Optional self-registration for FPO and vendor forms: the applicant chooses a password with the form.
 * The login (username = their email) is created at once but cannot sign in until the email link is opened.
 * It sees only its own record, and stays Pending until the Federation verifies it, exactly like other logins.
 * Without email (MAIL_ENABLED=false) the password is ignored and the login is created on verification as before.
 */
@Service
public class SelfRegistration {
  private static final Logger log = LoggerFactory.getLogger(SelfRegistration.class);
  public static final Set<String> FORMS = Set.of("fpo", "vendor");
  public static final Duration CONFIRM_TTL = Duration.ofHours(48);

  /** What the form response tells the applicant about their login. */
  public enum Outcome { CONFIRM_EMAIL, AFTER_VERIFICATION, NOT_REQUESTED }

  private final UserRepository users;
  private final MongoTemplate mongo;
  private final PasswordEncoder encoder;
  private final Notifications notifications;
  private final MailProperties mail;
  private final AuditService audit;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public SelfRegistration(UserRepository users, MongoTemplate mongo, PasswordEncoder encoder, Notifications notifications,
      MailProperties mail, AuditService audit, Clock clock) {
    this.users = users; this.mongo = mongo; this.encoder = encoder; this.notifications = notifications;
    this.mail = mail; this.audit = audit; this.clock = clock;
  }

  public static boolean requested(String formKey, String password) {
    return FORMS.contains(formKey) && password != null && !password.isEmpty();
  }

  /** Checks the password and that the email is free, BEFORE the registration is saved. */
  public Outcome precheck(String formKey, String password, String passwordConfirm, String email) {
    if (!requested(formKey, password)) return Outcome.NOT_REQUESTED;
    if (!mail.enabled()) return Outcome.AFTER_VERIFICATION;
    String username = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    String problem = Passwords.problem(password, username);
    if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem, Map.of("password", problem));
    if (passwordConfirm != null && !password.equals(passwordConfirm))
      throw new ApiException(HttpStatus.BAD_REQUEST, "The passwords do not match.", Map.of("passwordConfirm", "The passwords do not match."));
    if (users.existsByUsername(username))
      throw new ApiException(HttpStatus.CONFLICT, "This email already has a login. Please sign in, or use another email.",
          Map.of("email", "This email already has a login."));
    return Outcome.CONFIRM_EMAIL;
  }

  /** Creates the unconfirmed login for a just-saved registration and emails the confirmation link. */
  public Outcome create(Registration r, String password) {
    Role role = AccountService.roleFor(r.getKind());
    String email = r.getData().get("email");
    if (role == null || email == null) return Outcome.AFTER_VERIFICATION;
    User u = new User(email, encoder.encode(password), role, r.getName(), r.getState(), r.getId());
    u.setEmailUnconfirmed(true);
    u.setCreatedBy("self-registration");
    try {
      u = users.save(u);
    } catch (DuplicateKeyException e) { // the same email registered at the same moment
      return Outcome.AFTER_VERIFICATION;
    }
    audit.log(u.getId(), u.getName(), role.code(), "user_self_registered", "user", u.getId(), u.getUsername() + " for " + r.getName());
    sendLink(u, r.getLang());
    return Outcome.CONFIRM_EMAIL;
  }

  /** Same public answer whether or not the login exists. */
  public void resend(String username) {
    if (!mail.enabled()) return;
    users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
        .filter((u) -> u.isActive() && u.isEmailUnconfirmed())
        .ifPresent((u) -> sendLink(u, null));
  }

  /** Opens the login after the emailed link is used. One use only; expires after 48 hours. */
  public boolean confirm(String token) {
    Query q = Query.query(Criteria.where("confirmTokenHash").is(PasswordResetController.hash(token))
        .and("confirmExpiresAt").gt(Instant.now(clock)).and("emailUnconfirmed").is(true));
    User u = mongo.findAndModify(q, new Update().set("emailUnconfirmed", false).unset("confirmTokenHash").unset("confirmExpiresAt"),
        FindAndModifyOptions.options().returnNew(true), User.class);
    if (u == null) return false;
    audit.log(u.getId(), u.getName(), u.getRole().code(), "email_confirmed", "user", u.getId(), u.getUsername());
    return true;
  }

  private void sendLink(User u, String lang) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = Instant.now(clock).plus(CONFIRM_TTL);
    mongo.updateFirst(Query.query(Criteria.where("_id").is(u.getId())),
        new Update().set("confirmTokenHash", PasswordResetController.hash(token)).set("confirmExpiresAt", expires), User.class);
    try {
      notifications.confirmEmail(u, token, lang, expires);
    } catch (RuntimeException e) {
      log.error("Confirmation email could not be queued");
    }
  }
}
