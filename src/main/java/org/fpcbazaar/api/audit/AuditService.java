package org.fpcbazaar.api.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.fpcbazaar.api.common.ClientIp;
import org.fpcbazaar.api.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Activity log. A failure to write the log never breaks the action itself. */
@Service
public class AuditService {
  private static final Logger log = LoggerFactory.getLogger(AuditService.class);
  private final AuditRepository repo;
  private final MongoTemplate mongo;
  private final Clock clock;

  public AuditService(AuditRepository repo, MongoTemplate mongo, Clock clock) {
    this.repo = repo;
    this.mongo = mongo;
    this.clock = clock;
  }

  public void log(CurrentUser actor, String action, String targetType, String targetId, String summary) {
    log(actor == null ? null : actor.id(), actor == null ? null : actor.name(), actor == null ? null : actor.role().code(),
        action, targetType, targetId, summary);
  }

  public void log(String actorId, String actorName, String actorRole, String action, String targetType, String targetId,
      String summary) {
    try {
      repo.save(new AuditEntry(null, Instant.now(clock), actorId, actorName, actorRole, action, targetType, targetId,
          summary, currentIp()));
    } catch (RuntimeException e) {
      log.warn("Could not write audit entry {}: {}", action, e.getMessage());
    }
  }

  public List<AuditEntry> latest(String action, int limit) {
    Query q = new Query().with(Sort.by(Sort.Direction.DESC, "at")).limit(Math.max(1, Math.min(limit, 500)));
    if (action != null && !action.isBlank()) q.addCriteria(Criteria.where("action").is(action.trim()));
    return mongo.find(q, AuditEntry.class);
  }

  private static String currentIp() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest req = a.getRequest();
      return ClientIp.of(req);
    }
    return null;
  }
}
