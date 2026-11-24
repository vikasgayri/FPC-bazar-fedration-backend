package org.fpcbazaar.api.users;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.forms.FormSchemas;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.registrations.Status;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.Passwords;
import org.fpcbazaar.api.security.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login management. Admin manages every login; a State Head manages the Project Heads of their own state.
 * New logins and resets get a one-time password that must be changed at first sign-in.
 */
@Service
public class UserAdminService {
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._@+-]{2,119}$");

  private final org.fpcbazaar.api.notifications.Notifications notifications;
  private final UserRepository users;
  private final RegistrationRepository registrations;
  private final MongoTemplate mongo;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final UserWrites writes;
  private final org.fpcbazaar.api.files.FileStorage storage;
  private final LoginOtpService otp;

  public UserAdminService(UserRepository users, RegistrationRepository registrations, MongoTemplate mongo,
      PasswordEncoder encoder, AuditService audit, UserWrites writes, org.fpcbazaar.api.notifications.Notifications notifications,
      org.fpcbazaar.api.files.FileStorage storage, LoginOtpService otp) {
    this.users = users;
    this.storage = storage;
    this.otp = otp;
    this.notifications = notifications;
    this.writes = writes;
    this.registrations = registrations;
    this.mongo = mongo;
    this.encoder = encoder;
    this.audit = audit;
  }

  public record CreateRequest(String username, String name, String role, String state, String recordId) {}

  public record UpdateRequest(String name, String state, Boolean active) {}

  public record Issued(UserView user, String tempPassword) {}

  static boolean canManage(CurrentUser actor, Role targetRole, String targetState) {
    if (actor.role() == Role.ADMIN) return true;
    return actor.role() == Role.STATE_HEAD && targetRole == Role.PROJECT_HEAD && actor.state() != null
        && actor.state().equals(targetState);
  }

  public List<User> list(CurrentUser actor, String role, String state, String q) {
    List<Criteria> all = new ArrayList<>();
    if (actor.role() == Role.STATE_HEAD) {
      all.add(Criteria.where("role").is(Role.PROJECT_HEAD));
      all.add(Criteria.where("state").is(actor.state()));
    } else if (actor.role() != Role.ADMIN) {
      throw ApiException.forbidden("You are not allowed to do this.");
    }
    if (notBlank(role)) all.add(Criteria.where("role").is(parseRole(role)));
    if (notBlank(state)) all.add(Criteria.where("state").is(state.trim()));
    if (notBlank(q)) {
      Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
      all.add(new Criteria().orOperator(Criteria.where("username").regex(p), Criteria.where("name").regex(p)));
    }
    Query query = (all.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(all.toArray(new Criteria[0]))))
        .with(Sort.by("role", "username")).limit(1000);
    return mongo.find(query, User.class);
  }

  public Issued create(CurrentUser actor, CreateRequest req) {
    if (req == null) throw ApiException.badRequest("The request could not be read.");
    Role role = parseRole(req.role());
    String username = req.username() == null ? "" : req.username().trim().toLowerCase(Locale.ROOT);
    String name = req.name() == null ? "" : req.name().strip();
    String state = notBlank(req.state()) ? req.state().trim() : null;
    String recordId = null;

    // Every login is an email: the sign-in code (second step) is sent there.
    if (!USERNAME.matcher(username).matches() || !AccountService.EMAIL.matcher(username).matches())
      throw invalid("username", "Enter the person's email address. The sign-in code is sent there.");
    if (name.isEmpty() || name.length() > 100) throw invalid("name", "Enter a name (maximum 100 characters).");

    if (role == Role.VENDOR || role == Role.FPO) {
      Registration r = registrations.findById(notBlank(req.recordId()) ? req.recordId() : "-").orElse(null);
      if (r == null || AccountService.roleFor(r.getKind()) != role || r.getStatus() != Status.VERIFIED)
        throw invalid("recordId", "Choose a verified " + role.code() + " registration.");
      if (users.findFirstByRecordId(r.getId()).isPresent()) throw invalid("recordId", "This registration already has a login.");
      recordId = r.getId();
      state = r.getState();
    } else if (role == Role.STATE_HEAD || role == Role.PROJECT_HEAD) {
      if (state == null || !FormSchemas.STATES.contains(state)) throw invalid("state", "Choose a state.");
    } else {
      state = null;
    }
    if (!canManage(actor, role, state)) throw ApiException.forbidden("You are not allowed to do this.");
    if (users.existsByUsername(username)) throw new ApiException(HttpStatus.CONFLICT, "This username is already taken.",
        Map.of("username", "Already taken."));

    String temp = Passwords.temporary();
    User u = new User(username, encoder.encode(temp), role, name, state, recordId);
    u.setMustChangePassword(true);
    u.setCreatedBy(actor.name());
    try {
      u = users.save(u);
    } catch (DuplicateKeyException e) {
      throw new ApiException(HttpStatus.CONFLICT, "This username is already taken.", Map.of("username", "Already taken."));
    }
    audit.log(actor, "user_created", "user", u.getId(), role.code() + " " + username + (state == null ? "" : " (" + state + ")"));
    notifications.account(u, temp, false);
    return new Issued(UserView.of(u), temp);
  }

  // This deployment is one API process: serialize count-and-disable so concurrent admins cannot disable each other.
  public synchronized User update(CurrentUser actor, String id, UpdateRequest req) {
    if (req == null) throw ApiException.badRequest("The request could not be read.");
    User u = manageable(actor, id);
    Update update = new Update();
    boolean endSessions = false;
    List<String> changes = new ArrayList<>();
    if (req.name() != null) {
      String name = req.name().strip();
      if (name.isEmpty() || name.length() > 100) throw invalid("name", "Enter a name (maximum 100 characters).");
      if (!name.equals(u.getName())) {
        update.set("name", name);
        changes.add("name");
      }
    }
    if (req.state() != null && !req.state().equals(u.getState())) {
      if (u.getRole() != Role.STATE_HEAD && u.getRole() != Role.PROJECT_HEAD) throw invalid("state", "Only State Heads and Project Heads have a state.");
      if (!FormSchemas.STATES.contains(req.state())) throw invalid("state", "Choose a state.");
      if (!canManage(actor, u.getRole(), req.state())) throw ApiException.forbidden("You are not allowed to do this.");
      update.set("state", req.state());
      endSessions = true;
      changes.add("state=" + req.state());
    }
    if (req.active() != null && req.active() != u.isActive()) {
      if (!req.active()) {
        if (u.getId().equals(actor.id())) throw ApiException.badRequest("You cannot switch off your own login.");
        if (u.getRole() == Role.ADMIN && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1)
          throw ApiException.badRequest("At least one admin login must stay active.");
      }
      update.set("active", req.active());
      endSessions = true;
      changes.add(req.active() ? "activated" : "deactivated");
    }
    if (changes.isEmpty()) return u;
    if (endSessions) update.inc("tokenVersion", 1);
    User saved = writes.apply(u.getId(), update);
    if (saved == null) throw ApiException.notFound("Login not found.");
    audit.log(actor, "user_updated", "user", saved.getId(), saved.getUsername() + ": " + String.join(", ", changes));
    return saved;
  }

  public record Deleted(String username, String role, boolean applicationDeleted, int filesDeleted) {}

  /**
   * Admin only. Permanently removes a login from the database. For a vendor / FPO login it also removes the
   * linked application (registration) and its uploaded documents, so the public page disappears too.
   * The activity log keeps a line saying who deleted what. Cannot be undone (only from a backup).
   * Synchronized with {@link #update}: the "last admin" check cannot race with a switch-off.
   */
  public synchronized Deleted delete(CurrentUser actor, String id) {
    if (actor.role() != Role.ADMIN) throw ApiException.forbidden("Only an admin can delete a login.");
    User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Login not found."));
    if (u.getId().equals(actor.id())) throw ApiException.badRequest("You cannot delete your own login.");
    if (u.getRole() == Role.ADMIN && u.isActive() && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1)
      throw ApiException.badRequest("At least one admin login must stay active.");

    // 1. The login: from now on its tokens fail (AccountCheckFilter finds no user) and pending sign-ins are void.
    if (mongo.remove(Query.query(Criteria.where("_id").is(u.getId())), User.class).getDeletedCount() != 1)
      throw ApiException.notFound("Login not found.");
    otp.cancelFor(u.getId());

    // 2. Vendor / FPO: the application and its documents.
    boolean applicationDeleted = false;
    int files = 0;
    String what = "";
    if (u.getRecordId() != null) {
      Registration r = registrations.findById(u.getRecordId()).orElse(null);
      if (r != null) {
        registrations.deleteById(r.getId());
        applicationDeleted = true;
        for (org.fpcbazaar.api.files.StoredFile f : r.getFiles().values()) {
          storage.deleteQuietly(f);
          files++;
        }
        what = " with application " + r.getId() + " (" + r.getKind() + ", " + r.getName() + ") and " + files + " document(s)";
      }
    }
    audit.log(actor, "user_deleted", "user", u.getId(), u.getRole().code() + " " + u.getUsername() + what);
    return new Deleted(u.getUsername(), u.getRole().code(), applicationDeleted, files);
  }

  public Issued resetPassword(CurrentUser actor, String id) {
    User u = manageable(actor, id);
    if (u.getId().equals(actor.id())) throw ApiException.badRequest("Use 'change password' for your own login.");
    String temp = Passwords.temporary();
    User saved = writes.apply(u.getId(), new Update().set("passwordHash", encoder.encode(temp))
        .set("mustChangePassword", true).set("emailUnconfirmed", false).inc("tokenVersion", 1));
    if (saved == null) throw ApiException.notFound("Login not found.");
    audit.log(actor, "password_reset", "user", saved.getId(), saved.getUsername());
    notifications.account(saved, temp, true);
    return new Issued(UserView.of(saved), temp);
  }

  private User manageable(CurrentUser actor, String id) {
    User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Login not found."));
    if (!canManage(actor, u.getRole(), u.getState())) {
      if (actor.role() == Role.ADMIN || actor.role() == Role.STATE_HEAD) throw ApiException.notFound("Login not found.");
      throw ApiException.forbidden("You are not allowed to do this.");
    }
    return u;
  }

  private static Role parseRole(String code) {
    try {
      return Role.fromCode(code == null ? "" : code.trim());
    } catch (IllegalArgumentException e) {
      throw invalid("role", "Choose admin, state_head, project_head, vendor or fpo.");
    }
  }

  private static ApiException invalid(String field, String msg) {
    return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
  }

  private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
