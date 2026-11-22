package org.fpcbazaar.api.enquiries;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.Role;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Enquiries inbox. Only admins see enquiries (they are not tied to one state). */
@Service
public class EnquiryService {
  public static final Set<String> STATUSES = Set.of("new", "replied", "closed");
  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final int NOTE_MAX = 1000;

  private final EnquiryRepository repo;
  private final MongoTemplate mongo;
  private final AuditService audit;
  private final Clock clock;

  public EnquiryService(EnquiryRepository repo, MongoTemplate mongo, AuditService audit, Clock clock) {
    this.repo = repo;
    this.mongo = mongo;
    this.audit = audit;
    this.clock = clock;
  }

  public record UpdateRequest(String status, String note) {}

  public static void requireAdmin(CurrentUser u) {
    if (u.role() != Role.ADMIN) throw ApiException.forbidden("You are not allowed to do this.");
  }

  /** from / to are dates (yyyy-MM-dd, India time), both inclusive. */
  public List<Enquiry> list(CurrentUser u, String formKey, String status, String q, String from, String to, int limit) {
    requireAdmin(u);
    List<Criteria> all = new ArrayList<>();
    if (notBlank(formKey)) all.add(Criteria.where("formKey").is(formKey.trim()));
    if (notBlank(status)) {
      if (!STATUSES.contains(status.trim())) throw ApiException.badRequest("Unknown status.");
      all.add(Criteria.where("status").is(status.trim()));
    }
    if (notBlank(from) || notBlank(to)) {
      Criteria c = Criteria.where("createdAt");
      if (notBlank(from)) c = c.gte(day(from).atStartOfDay(INDIA).toInstant());
      if (notBlank(to)) c = c.lt(day(to).plusDays(1).atStartOfDay(INDIA).toInstant());
      all.add(c);
    }
    if (notBlank(q)) {
      Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
      all.add(new Criteria().orOperator(Criteria.where("data.name").regex(p), Criteria.where("data.phone").regex(p),
          Criteria.where("data.email").regex(p), Criteria.where("data.organisation").regex(p),
          Criteria.where("data.message").regex(p), Criteria.where("data.subject").regex(p)));
    }
    Query query = (all.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(all.toArray(new Criteria[0]))))
        .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit);
    return mongo.find(query, Enquiry.class);
  }

  public Enquiry get(CurrentUser u, String id) {
    requireAdmin(u);
    return repo.findById(id).orElseThrow(() -> ApiException.notFound("Enquiry not found."));
  }

  public Enquiry update(CurrentUser u, String id, UpdateRequest req) {
    Enquiry e = get(u, id);
    if (req == null) throw ApiException.badRequest("The request could not be read.");
    if (req.status() != null) {
      if (!STATUSES.contains(req.status())) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose new, replied or closed.",
          Map.of("status", "Invalid status."));
      e.setStatus(req.status());
    }
    if (req.note() != null) {
      String note = req.note().strip();
      if (note.length() > NOTE_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "Note is too long.",
          Map.of("note", "Maximum " + NOTE_MAX + " characters."));
      e.setNote(note);
    }
    e.setHandledAt(Instant.now(clock));
    e.setHandledBy(u.name());
    e = repo.save(e);
    audit.log(u, "enquiry_updated", "enquiry", e.getId(), e.getFormKey() + " -> " + e.getStatus());
    return e;
  }

  private static LocalDate day(String s) {
    try {
      return LocalDate.parse(s.trim());
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("Dates must look like 2026-11-19.");
    }
  }

  private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
