package org.fpcbazaar.api.registrations;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.files.FileStorage;
import org.fpcbazaar.api.files.StoredFile;
import org.fpcbazaar.api.forms.FormSchemas;
import org.fpcbazaar.api.forms.FormValidator;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.users.AccountNotice;
import org.fpcbazaar.api.users.AccountService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creates registrations from the public forms and lets staff review them.
 * Role scope is enforced here, never only in the frontend:
 * admin = all, state_head / project_head = own state, vendor / fpo = own record.
 */
@Service
public class RegistrationService {
  private static final int LIST_LIMIT = 500;
  private static final int REASON_MAX = 500;

  private final RegistrationRepository repo;
  private final MongoTemplate mongo;
  private final FileStorage storage;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final AccountService accounts;
  private final AuditService audit;

  public RegistrationService(RegistrationRepository repo, MongoTemplate mongo, FileStorage storage,
      ApplicationEventPublisher events, Clock clock, AccountService accounts, AuditService audit) {
    this.repo = repo;
    this.accounts = accounts;
    this.audit = audit;
    this.mongo = mongo;
    this.storage = storage;
    this.events = events;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- public form

  public Registration create(String formKey, FormValidator.Result form, String lang, String page) {
    String kind = FormSchemas.REGISTRATION_KINDS.get(formKey);
    Map<String, String> v = form.values();
    rejectDuplicate(kind, v, null);

    Registration r = new Registration();
    r.setKind(kind);
    r.setName(firstNonBlank(v.get("fpoName"), v.get("companyName"), v.get("ngoName"), v.get("name")));
    r.setState(firstNonBlank(v.get("state"), v.get("supplyState")));
    Map<String, String> data = new LinkedHashMap<>(v);
    data.remove("fpoName");
    data.remove("companyName");
    data.remove("ngoName");
    if (v.get("name") != null) data.put("contact", v.get("name"));
    r.setData(data);
    r.setUniqueKey(uniqueKey(kind, data));
    r.setLang("hi".equals(lang) ? "hi" : "en");
    r.setPage(page == null ? null : page.substring(0, Math.min(page.length(), 200)));
    r.setCreatedAt(Instant.now(clock));

    Map<String, StoredFile> saved = new LinkedHashMap<>();
    Registration out;
    try {
      for (Map.Entry<String, MultipartFile> e : form.files().entrySet()) saved.put(e.getKey(), storage.store(e.getValue()));
      r.setFiles(saved);
      try {
        out = "vendor".equals(kind) ? saveWithSlug(r) : repo.save(r);
      } catch (DuplicateKeyException e) {
        throw duplicateOrRethrow(kind, e);
      }
    } catch (RuntimeException e) {
      saved.values().forEach(storage::deleteQuietly);
      throw e;
    }
    events.publishEvent(new RegistrationSubmittedEvent(out.getId(), out.getKind(), out.getState()));
    return out;
  }

  private static final Map<String, String> UNIQUE_FIELD = Map.of("fpo", "cin", "vendor", "gst", "ngo", "darpanId");
  private static final Map<String, String> UNIQUE_LABEL = Map.of("cin", "CIN", "gst", "GST number", "darpanId", "NGO Darpan ID");

  /**
   * The same CIN (FPO), GST (vendor) or Darpan ID (NGO) cannot be pending or verified twice: checked for new forms
   * and again when a record is moved back to pending / verified. {@code exceptId} is the record being decided.
   */
  private void rejectDuplicate(String kind, Map<String, String> v, String exceptId) {
    String field = UNIQUE_FIELD.get(kind);
    if (field == null || v.get(field) == null) return;
    Criteria c = Criteria.where("kind").is(kind).and("data." + field).is(v.get(field))
        .and("status").in(Status.PENDING, Status.VERIFIED);
    if (exceptId != null) c = c.and("_id").ne(exceptId);
    if (mongo.exists(Query.query(c), Registration.class)) throw duplicate(field);
  }

  static String uniqueKey(String kind, Map<String, String> data) {
    String field = UNIQUE_FIELD.get(kind);
    String value = field == null ? null : data.get(field);
    return value == null || value.isBlank() ? null : kind + ":" + value;
  }

  private static ApiException duplicate(String field) {
    String what = UNIQUE_LABEL.get(field);
    return new ApiException(HttpStatus.CONFLICT, "This " + what + " is already registered.",
        Map.of(field, "This " + what + " is already registered."));
  }

  /** A clash on the unique_active index means a duplicate CIN / GST / Darpan ID that arrived at the same moment. */
  private static RuntimeException duplicateOrRethrow(String kind, DuplicateKeyException e) {
    String field = UNIQUE_FIELD.get(kind);
    if (field != null && String.valueOf(e.getMessage()).contains("unique_active")) return duplicate(field);
    return e;
  }

  private Registration saveWithSlug(Registration r) {
    String base = slugify(r.getName());
    String slug = base;
    for (int n = 2; repo.existsBySlug(slug); n++) slug = base + "-" + n;
    for (int attempt = 0; ; attempt++) {
      r.setSlug(slug);
      try {
        return repo.save(r);
      } catch (DuplicateKeyException e) { // two vendors with the same name at the same moment
        if (String.valueOf(e.getMessage()).contains("unique_active") || attempt >= 3) throw e;
        slug = base + "-" + UUID.randomUUID().toString().substring(0, 6);
      }
    }
  }

  static String slugify(String s) {
    String a = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
    String slug = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-$", "");
    return slug.isEmpty() ? "vendor" : slug;
  }

  // ---------------------------------------------------------------- admin panel

  public List<Registration> list(CurrentUser u, String kind, String status, String state, String q) {
    return list(u, kind, status, state, q, LIST_LIMIT);
  }

  public List<Registration> list(CurrentUser u, String kind, String status, String state, String q, int limit) {
    List<Criteria> all = new ArrayList<>();
    all.add(scope(u));
    if (notBlank(kind)) all.add(Criteria.where("kind").is(kind.trim()));
    if (notBlank(status)) {
      Status s = Status.fromCode(status);
      if (s == null) throw ApiException.badRequest("Unknown status.");
      all.add(Criteria.where("status").is(s));
    }
    if (notBlank(state)) all.add(Criteria.where("state").is(state.trim()));
    if (notBlank(q)) {
      Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
      all.add(new Criteria().orOperator(Criteria.where("name").regex(p), Criteria.where("data.contact").regex(p),
          Criteria.where("data.cin").regex(p), Criteria.where("data.gst").regex(p), Criteria.where("data.phone").regex(p)));
    }
    Query query = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])))
        .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit);
    return mongo.find(query, Registration.class);
  }

  /** Records outside the caller's scope are reported as "not found" so their existence is not revealed. */
  public Registration get(CurrentUser u, String id) {
    Registration r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Record not found."));
    if (!inScope(u, r)) throw ApiException.notFound("Record not found.");
    return r;
  }

  /** Result of a decision; {@code account} is set when a vendor / FPO login was checked or created. */
  public record Decision(Registration registration, AccountNotice account) {}

  public Decision decide(CurrentUser u, String id, String statusCode, String reasonRaw) {
    if (!u.role().canDecide()) throw ApiException.forbidden("You are not allowed to do this.");
    Status to = Status.fromCode(statusCode);
    if (to == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose verified, rejected or pending.", Map.of("status", "Invalid status."));
    String reason = reasonRaw == null ? "" : reasonRaw.strip();
    if (to == Status.REJECTED && reason.isEmpty())
      throw new ApiException(HttpStatus.BAD_REQUEST, "Please write the reason for rejection.", Map.of("reason", "Reason is required."));
    if (reason.length() > REASON_MAX)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Reason is too long.", Map.of("reason", "Maximum " + REASON_MAX + " characters."));
    if (to != Status.REJECTED) reason = "";

    Registration r = get(u, id);
    Status from = r.getStatus();
    if (from == to && reason.equals(r.getReason()))
      return new Decision(r, to == Status.VERIFIED ? accounts.ensureFor(r, u) : null);

    if (to != Status.REJECTED) {
      rejectDuplicate(r.getKind(), r.getData(), r.getId());
      r.setUniqueKey(uniqueKey(r.getKind(), r.getData()));
    }
    Instant now = Instant.now(clock);
    r.setStatus(to);
    r.setReason(reason);
    r.setDecidedAt(now);
    r.setDecidedBy(u.name());
    r.setDecidedById(u.id());
    r.getHistory().add(new Registration.HistoryEntry(now, u.id(), u.name(), from, to, reason));
    Registration saved;
    try {
      saved = repo.save(r);
    } catch (DuplicateKeyException e) {
      throw duplicateOrRethrow(r.getKind(), e);
    } catch (OptimisticLockingFailureException e) {
      throw new ApiException(HttpStatus.CONFLICT, "Someone else changed this record just now. Please reload and try again.");
    }
    audit.log(u, "registration_decided", saved.getKind(), saved.getId(),
        saved.getName() + ": " + from.code() + " -> " + to.code() + (reason.isEmpty() ? "" : " (" + reason + ")"));
    AccountNotice account = to == Status.VERIFIED ? accounts.ensureFor(saved, u) : null;
    events.publishEvent(new RegistrationDecidedEvent(saved.getId(), saved.getKind(), from, to, reason, u.name()));
    return new Decision(saved, account);
  }

  public StoredFile file(CurrentUser u, String id, String key) {
    StoredFile f = get(u, id).getFiles().get(key);
    if (f == null) throw ApiException.notFound("Document not found.");
    return f;
  }

  // ---------------------------------------------------------------- scope

  /** Mongo filter for the records a user may see. */
  public static Criteria scope(CurrentUser u) {
    return switch (u.role().scope()) {
      case ALL -> new Criteria();
      case STATE -> notBlank(u.state()) ? Criteria.where("state").is(u.state()) : Criteria.where("_id").is(null);
      case OWN -> notBlank(u.recordId()) ? Criteria.where("_id").is(u.recordId()) : Criteria.where("_id").is(null);
    };
  }

  static boolean inScope(CurrentUser u, Registration r) {
    return switch (u.role().scope()) {
      case ALL -> true;
      case STATE -> notBlank(u.state()) && u.state().equals(r.getState());
      case OWN -> notBlank(u.recordId()) && u.recordId().equals(r.getId());
    };
  }

  private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

  private static String firstNonBlank(String... values) {
    for (String v : values) if (notBlank(v)) return v;
    return null;
  }
}
