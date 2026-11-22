package org.fpcbazaar.api.users;

import java.util.Locale;
import java.util.regex.Pattern;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.Passwords;
import org.fpcbazaar.api.security.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Vendor and FPO logins: created automatically (username = their email) when the registration is verified. */
@Service
public class AccountService {
  static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private final org.fpcbazaar.api.notifications.Notifications notifications;
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AuditService audit;

  public AccountService(UserRepository users, PasswordEncoder encoder, AuditService audit, org.fpcbazaar.api.notifications.Notifications notifications) {
    this.users = users;
    this.notifications = notifications;
    this.encoder = encoder;
    this.audit = audit;
  }

  public static Role roleFor(String kind) {
    return switch (kind == null ? "" : kind) {
      case "vendor" -> Role.VENDOR;
      case "fpo" -> Role.FPO;
      default -> null;
    };
  }

  /** Called after a vendor / FPO registration is verified. Never throws for "expected" problems; it explains them. */
  public AccountNotice ensureFor(Registration r, CurrentUser actor) {
    Role role = roleFor(r.getKind());
    if (role == null) return null;
    User existing = users.findFirstByRecordId(r.getId()).orElse(null);
    if (existing != null) {
      // Verifying never switches a login back on: that is a login-management decision (admin, in Users).
      if (!existing.isActive())
        return new AccountNotice(false, existing.getUsername(), null,
            "Login " + existing.getUsername() + " is switched off. Only an admin can switch it on (Users).");
      return new AccountNotice(false, existing.getUsername(), null, "Login already exists: " + existing.getUsername());
    }
    String email = r.getData().get("email");
    email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(email).matches())
      return new AccountNotice(false, null, null, "No email on this registration. Create the login from Users.");
    if (users.existsByUsername(email))
      return new AccountNotice(false, email, null, "This email is already used by another login. Create the login from Users.");
    String temp = Passwords.temporary();
    User u = new User(email, encoder.encode(temp), role, r.getName(), r.getState(), r.getId());
    u.setMustChangePassword(true);
    u.setCreatedBy(actor.name());
    try {
      u = users.save(u);
    } catch (DuplicateKeyException e) {
      return new AccountNotice(false, email, null, "This email is already used by another login. Create the login from Users.");
    }
    audit.log(actor, "user_created", "user", u.getId(), role.code() + " " + email + " for " + r.getName());
    notifications.account(u, temp, false);
    return new AccountNotice(true, email, temp, "Login created. Share this one-time password with the " + role.code() + ".");
  }
}
