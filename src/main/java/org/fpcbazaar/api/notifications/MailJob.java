package org.fpcbazaar.api.notifications;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("mail_jobs")
@CompoundIndex(name = "mail_due", def = "{'status':1,'nextAttempt':1}")
public class MailJob {
  @Id public String id;
  public String kind;
  public String payload;
  public String status = "pending";
  public int attempts;
  public Instant createdAt;
  public Instant nextAttempt;
  public Instant completedAt;
  public Instant expiresAt;
  public String lastError;
  public String providerMessageId;
  public String claim;
  // Completed/abandoned jobs are removed after 30 days; pending jobs have no purgeAt.
  @Indexed(expireAfter = "0s") public Instant purgeAt;
  public MailJob() {}
}
