package org.fpcbazaar.api.users;

import java.time.Instant;
import org.fpcbazaar.api.security.Role;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Staff and partner logins: admin, state heads, project heads, and verified vendors / FPOs. */
@Document("users")
public class User {
  @Id private String id;
  @Indexed(unique = true) private String username; // always lower-case
  private String passwordHash;
  private Role role;
  private String name;
  private String state;    // state key (e.g. "rajasthan") for state_head / project_head
  private String recordId; // registration id for vendor / fpo logins
  private boolean active = true;
  private Instant createdAt = Instant.now();
  private Instant lastLoginAt;

  public User() {}

  public User(String username, String passwordHash, Role role, String name, String state, String recordId) {
    this.username = username.trim().toLowerCase();
    this.passwordHash = passwordHash;
    this.role = role;
    this.name = name;
    this.state = state;
    this.recordId = recordId;
  }

  public String getId() { return id; }
  public String getUsername() { return username; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public Role getRole() { return role; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public String getRecordId() { return recordId; }
  public void setRecordId(String recordId) { this.recordId = recordId; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getLastLoginAt() { return lastLoginAt; }
  public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
