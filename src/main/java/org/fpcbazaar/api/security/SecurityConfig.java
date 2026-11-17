package org.fpcbazaar.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;
import org.fpcbazaar.api.common.ApiError;
import org.fpcbazaar.api.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.core.convert.converter.Converter;

@Configuration
public class SecurityConfig {
  /** Document links are opened in a new browser tab, so only they may carry the token as ?token=. */
  private static final Pattern FILE_URL = Pattern.compile("^/api/admin/registrations/[^/]+/files/[^/]+$");

  @Bean
  SecurityFilterChain api(HttpSecurity http, JwtService jwt) throws Exception {
    http
        .csrf((c) -> c.disable()) // stateless bearer tokens, no cookies
        .cors((c) -> {})
        .sessionManagement((s) -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers((h) -> h.contentTypeOptions((o) -> {}).frameOptions((f) -> f.deny()))
        .authorizeHttpRequests((a) -> a
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/forms/*").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers("/api/auth/me", "/api/admin/**").authenticated()
            .anyRequest().denyAll())
        .oauth2ResourceServer((o) -> o
            .bearerTokenResolver(tokenResolver())
            .jwt((j) -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(roleConverter()))
            .authenticationEntryPoint((req, res, ex) -> write(res, HttpStatus.UNAUTHORIZED, "Please sign in again."))
            .accessDeniedHandler((req, res, ex) -> write(res, HttpStatus.FORBIDDEN, "You are not allowed to do this.")))
        .exceptionHandling((e) -> e
            .authenticationEntryPoint((req, res, ex) -> write(res, HttpStatus.UNAUTHORIZED, "Please sign in again."))
            .accessDeniedHandler((req, res, ex) -> write(res, HttpStatus.FORBIDDEN, "You are not allowed to do this.")));
    return http.build();
  }

  private static void write(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String msg) throws java.io.IOException {
    res.setStatus(status.value());
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write("{\"ok\":false,\"message\":\"" + msg + "\"}");
  }

  private static BearerTokenResolver tokenResolver() {
    DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    return (HttpServletRequest req) -> {
      String fromHeader = header.resolve(req);
      if (fromHeader != null) return fromHeader;
      if ("GET".equals(req.getMethod()) && FILE_URL.matcher(req.getRequestURI()).matches()) {
        String t = req.getParameter("token");
        return t == null || t.isBlank() ? null : t;
      }
      return null;
    };
  }

  private static Converter<Jwt, AbstractAuthenticationToken> roleConverter() {
    return (jwt) -> new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))), jwt.getSubject());
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(AppProperties props) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(props.cors() == null ? List.of() : props.cors().allowedOrigins());
    c.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    c.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/api/**", c);
    return src;
  }

  @Bean
  PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

  /** Keeps ApiError referenced for readers: every 401/403 above uses the same JSON shape. */
  @SuppressWarnings("unused")
  private static final Class<?> ERROR_SHAPE = ApiError.class;
}
