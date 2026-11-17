package org.fpcbazaar.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The signed-in staff/partner user, rebuilt from the JWT on every request. */
public record CurrentUser(String id, String username, String name, Role role, String state, String recordId) {
  public static CurrentUser from(Jwt jwt) {
    return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("username"), jwt.getClaimAsString("name"),
        Role.fromCode(jwt.getClaimAsString("role")), jwt.getClaimAsString("state"), jwt.getClaimAsString("recordId"));
  }
}
