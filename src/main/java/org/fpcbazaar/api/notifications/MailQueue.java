package org.fpcbazaar.api.notifications;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MailQueue {
  private static final Logger log = LoggerFactory.getLogger(MailQueue.class);
  private final MongoTemplate mongo;
  private final MailProperties props;
  private final MailCipher cipher;
  private final BrevoTransport transport;
  private final Clock clock;

  public MailQueue(MongoTemplate mongo, MailProperties props, MailCipher cipher, BrevoTransport transport, Clock clock) {
    this.mongo = mongo; this.props = props; this.cipher = cipher; this.transport = transport; this.clock = clock;
  }
  public boolean enabled() { return props.enabled(); }

  public boolean enqueue(String kind, MailPayload payload, Instant expiresAt) {
    if (!enabled() || !Notifications.isEmail(payload.to())) return false;
    MailJob job = new MailJob();
    job.id = UUID.randomUUID().toString(); job.kind = kind; job.payload = cipher.encrypt(payload);
    job.createdAt = Instant.now(clock); job.nextAttempt = job.createdAt; job.expiresAt = expiresAt;
    mongo.insert(job);
    return true;
  }

  @Scheduled(fixedDelayString = "${app.mail.poll-delay:10000}", initialDelayString = "${app.mail.initial-delay:10000}")
  public void drain() {
    if (!enabled()) return;
    for (int i = 0; i < 20; i++) {
      Instant now = Instant.now(clock);
      Criteria due = new Criteria().orOperator(Criteria.where("status").is("pending"), Criteria.where("status").is("sending"))
          .and("nextAttempt").lte(now);
      Query q = Query.query(due).with(Sort.by("nextAttempt"));
      String claim = UUID.randomUUID().toString();
      MailJob job = mongo.findAndModify(q, new Update().set("status", "sending").set("claim", claim)
          .set("nextAttempt", now.plusSeconds(120)).inc("attempts", 1),
          FindAndModifyOptions.options().returnNew(true), MailJob.class);
      if (job == null) return;
      Query owned = Query.query(Criteria.where("_id").is(job.id).and("claim").is(claim));
      if (job.expiresAt != null && !job.expiresAt.isAfter(now)) {
        mongo.updateFirst(owned, finished("expired", now).set("lastError", "credential_expired"), MailJob.class);
        continue;
      }
      String providerMessageId;
      try {
        providerMessageId = transport.send(cipher.decrypt(job.payload));
      } catch (RuntimeException e) {
        boolean retry = e instanceof BrevoTransport.DeliveryFailure d && d.retryable && job.attempts < props.maxAttempts();
        String code = e instanceof BrevoTransport.DeliveryFailure ? e.getMessage() : "payload_error";
        Update update = retry ? new Update().set("status", "pending").set("nextAttempt",
            now.plusSeconds(Math.min(3600, 30L << Math.min(job.attempts - 1, 7)))) : new Update().set("status", "failed").set("completedAt", now).set("purgeAt", now.plus(Duration.ofDays(30)));
        // Keep encrypted payload for failed jobs so an administrator can explicitly retry.
        mongo.updateFirst(owned, update.set("lastError", code).unset("claim"), MailJob.class);
        log.warn("Mail job {} failed ({}, retry={})", job.id, code, retry);
        continue;
      }
      // If Mongo fails after provider acceptance the lease permits redelivery; delivery is at-least-once.
      mongo.updateFirst(owned, finished(props.sandbox() ? "sandboxed" : "accepted", now).set("providerMessageId", providerMessageId).unset("lastError"), MailJob.class);
    }
  }

  private static Update finished(String status, Instant now) {
    return new Update().set("status", status).set("completedAt", now).set("purgeAt", now.plus(Duration.ofDays(30)))
        .unset("payload").unset("claim");
  }
}
