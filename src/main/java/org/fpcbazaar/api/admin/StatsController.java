package org.fpcbazaar.api.admin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.fpcbazaar.api.audit.AuditEntry;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.enquiries.EnquiryRepository;
import org.fpcbazaar.api.enquiries.EnquiryService;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationService;
import org.fpcbazaar.api.registrations.Status;
import org.fpcbazaar.api.security.Auth;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.Role;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard numbers (admin panel), live counts for the 17 States Map (public) and the activity log (admin). */
@RestController
public class StatsController {
  private final MongoTemplate mongo;
  private final EnquiryRepository enquiries;
  private final AuditService audit;

  public StatsController(MongoTemplate mongo, EnquiryRepository enquiries, AuditService audit) {
    this.mongo = mongo;
    this.enquiries = enquiries;
    this.audit = audit;
  }

  /** {@code registrations: { fpo: { pending, verified, rejected }, ... }}, within the caller's scope. */
  @GetMapping("/api/admin/stats")
  public Map<String, Object> admin() {
    CurrentUser u = Auth.current();
    Map<String, Map<String, Long>> byKind = new TreeMap<>();
    count(RegistrationService.scope(u), "kind", "status").forEach((d) -> {
      Document id = (Document) d.get("_id");
      String status = Status.valueOf(id.getString("status")).code();
      byKind.computeIfAbsent(id.getString("kind"), (k) -> blank()).put(status, ((Number) d.get("n")).longValue());
    });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("registrations", byKind);
    if (u.role() == Role.ADMIN) out.put("enquiriesNew", enquiries.countByStatus("new"));
    return out;
  }

  /** Verified FPOs and vendors per state, for the 17 States Map. */
  @GetMapping("/api/public/stats")
  public ResponseEntity<Map<String, Object>> publicStats() {
    Map<String, Map<String, Long>> states = new TreeMap<>();
    long fpo = 0;
    long vendor = 0;
    Criteria c = Criteria.where("status").is(Status.VERIFIED).and("kind").in("fpo", "vendor");
    for (Document d : count(c, "state", "kind")) {
      Document id = (Document) d.get("_id");
      long n = ((Number) d.get("n")).longValue();
      String kind = id.getString("kind");
      if (id.getString("state") == null) continue;
      states.computeIfAbsent(id.getString("state"), (k) -> new LinkedHashMap<>(Map.of("fpo", 0L, "vendor", 0L))).put(kind, n);
      if ("fpo".equals(kind)) fpo += n;
      else vendor += n;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("states", states);
    out.put("totals", Map.of("fpo", fpo, "vendor", vendor));
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(out);
  }

  @GetMapping("/api/admin/audit")
  public Map<String, Object> auditLog(@RequestParam(required = false) String action, @RequestParam(defaultValue = "200") int limit) {
    EnquiryService.requireAdmin(Auth.current());
    java.util.List<AuditEntry> items = audit.latest(action, limit);
    return Map.of("items", items);
  }

  private Iterable<Document> count(Criteria match, String a, String b) {
    Aggregation agg = Aggregation.newAggregation(Aggregation.match(match), Aggregation.group(a, b).count().as("n"));
    return mongo.aggregate(agg, Registration.class, Document.class).getMappedResults();
  }

  private static Map<String, Long> blank() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("pending", 0L);
    m.put("verified", 0L);
    m.put("rejected", 0L);
    return m;
  }
}
