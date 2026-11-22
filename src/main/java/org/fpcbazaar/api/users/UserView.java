package org.fpcbazaar.api.users;

import java.time.Instant;
import org.fpcbazaar.api.security.Role;

/** A login as shown in the admin panel. Never contains the password hash. */
public record UserView(String id, String username, String name, Role role, String state, String recordId, boolean active,
    boolean mustChangePassword, String createdBy, Instant createdAt, Instant lastLoginAt) {

  public static UserView of(User u) {
    return new UserView(u.getId(), u.getUsername(), u.getName(), u.getRole(), u.getState(), u.getRecordId(), u.isActive(),
        u.isMustChangePassword(), u.getCreatedBy(), u.getCreatedAt(), u.getLastLoginAt());
  }
}
