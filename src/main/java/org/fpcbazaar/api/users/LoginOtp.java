package org.fpcbazaar.api.users;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One pending sign-in that waits for its emailed code. Only a SHA-256 hash of the code is stored.
 * The id is a long random value that the browser keeps between the password step and the code step.
 * Mongo deletes the document after {@code purgeAt} (TTL index), so old challenges never pile up.
 */
@Document("login_otps")
public class LoginOtp {
  @Id public String id;
  @Indexed public String userId;
  public String codeHash;
  /** The user's tokenVersion at the password step: a password change or switch-off in between cancels the sign-in. */
  public int tokenVersion;
  public int attempts;
  public int sends;
  public Instant expiresAt;
  public Instant lastSentAt;
  @Indexed(expireAfter = "0s") public Instant purgeAt;
}
