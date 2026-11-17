package org.fpcbazaar.api.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything that changes between laptop, staging and the Infinitive Cloud server lives here
 * and is read from environment variables (see .env.example).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Jwt jwt,
    Cors cors,
    Storage storage,
    Admin admin,
    @DefaultValue("http://localhost:8080") String publicBaseUrl,
    @DefaultValue("false") boolean seedDemo,
    RateLimit rateLimit) {

  public record Jwt(String secret, @DefaultValue("12h") Duration ttl, @DefaultValue("fpc-bazaar-api") String issuer) {}

  public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {}

  /** Uploaded documents are stored on the server disk under this folder (never inside the web root). */
  public record Storage(@DefaultValue("./data/uploads") String dir, @DefaultValue("5") int maxFileMb) {}

  /** First admin account, created on startup only if no admin exists yet. */
  public record Admin(String username, String password, @DefaultValue("Federation Admin") String name) {}

  public record RateLimit(@DefaultValue("10") int formsPerMinute, @DefaultValue("5") int loginFailuresPerWindow,
      @DefaultValue("15m") Duration loginWindow) {}
}
