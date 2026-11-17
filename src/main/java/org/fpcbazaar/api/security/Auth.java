package org.fpcbazaar.api.security;

import org.fpcbazaar.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Access to the signed-in user inside controllers/services. */
public final class Auth {
  private Auth() {}

  public static CurrentUser current() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a instanceof JwtAuthenticationToken t) return CurrentUser.from(t.getToken());
    throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
  }
}
