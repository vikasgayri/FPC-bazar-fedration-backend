package org.fpcbazaar.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.fpcbazaar.api.users.User;
import org.fpcbazaar.api.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the token is verified. A token stops working at once when the login is deactivated, its password is
 * changed / reset, or its state changes. A login that must change its password can only do that (and read /me).
 * Not a @Component on purpose: it is added to the security chain only.
 */
public class AccountCheckFilter extends OncePerRequestFilter {
  private static final Set<String> ALLOWED_BEFORE_CHANGE = Set.of("/api/auth/me", "/api/auth/change-password");
  private final UserRepository users;

  public AccountCheckFilter(UserRepository users) { this.users = users; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
      Number tv = t.getToken().getClaim("tv");
      User u = users.findById(t.getToken().getSubject()).orElse(null);
      if (u == null || !u.isActive() || tv == null || tv.intValue() != u.getTokenVersion()) {
        SecurityContextHolder.clearContext();
        write(res, HttpStatus.UNAUTHORIZED, "{\"ok\":false,\"message\":\"Please sign in again.\"}");
        return;
      }
      if (u.isMustChangePassword() && !ALLOWED_BEFORE_CHANGE.contains(req.getRequestURI())) {
        write(res, HttpStatus.FORBIDDEN,
            "{\"ok\":false,\"message\":\"Please change your password first.\",\"code\":\"password_change_required\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }

  private static void write(HttpServletResponse res, HttpStatus status, String json) throws IOException {
    res.setStatus(status.value());
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write(json);
  }
}
