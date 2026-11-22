package org.fpcbazaar.api.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.enquiries.EnquiryService;
import org.fpcbazaar.api.security.Auth;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notifications")
public class MailAdminController {
  private final MongoTemplate mongo;
  private final MailProperties props;
  private final Clock clock;
  private final AuditService audit;
  public MailAdminController(MongoTemplate mongo, MailProperties props, Clock clock, AuditService audit) {
    this.mongo = mongo; this.props = props; this.clock = clock; this.audit = audit;
  }
  public record View(String id, String kind, String status, int attempts, Instant createdAt, Instant completedAt, String lastError, String providerMessageId) {}
  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "100") int limit) {
    EnquiryService.requireAdmin(Auth.current());
    List<View> items = mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
        .limit(Math.max(1, Math.min(limit, 500))), MailJob.class).stream()
        .map(j -> new View(j.id, j.kind, j.status, j.attempts, j.createdAt, j.completedAt, j.lastError, j.providerMessageId)).toList();
    return Map.of("enabled", props.enabled(), "sandbox", props.sandbox(), "items", items);
  }
  @PostMapping("/{id}/retry")
  public Map<String, Boolean> retry(@PathVariable String id) {
    EnquiryService.requireAdmin(Auth.current());
    if (!props.enabled()) throw ApiException.badRequest("Email is disabled.");
    Instant now = Instant.now(clock);
    Criteria c = Criteria.where("_id").is(id).and("status").is("failed").and("payload").exists(true)
        .orOperator(Criteria.where("expiresAt").is(null), Criteria.where("expiresAt").gt(now));
    var result = mongo.updateFirst(Query.query(c), new Update().set("status", "pending").set("attempts", 0)
        .set("nextAttempt", now).unset("completedAt").unset("purgeAt").unset("lastError"), MailJob.class);
    if (result.getModifiedCount() == 0) throw ApiException.badRequest("Only failed, unexpired notifications can be retried.");
    audit.log(Auth.current(), "mail_retry", "mail", id, "Notification retry requested");
    return Map.of("ok", true);
  }
}
