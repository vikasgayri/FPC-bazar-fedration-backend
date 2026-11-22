package org.fpcbazaar.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.users.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 tokens. The secret comes from JWT_SECRET (at least 32 characters). */
@Service
public class JwtService {
  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final AppProperties props;

  public JwtService(AppProperties props) {
    this.props = props;
    String secret = props.jwt() == null ? null : props.jwt().secret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwt().issuer()));
    this.decoder = d;
  }

  public JwtDecoder decoder() { return decoder; }

  public String issue(User u) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
        .issuer(props.jwt().issuer())
        .issuedAt(now)
        .expiresAt(now.plus(props.jwt().ttl()))
        .subject(u.getId())
        .claim("username", u.getUsername())
        .claim("name", u.getName())
        .claim("role", u.getRole().code())
        .claim("tv", u.getTokenVersion());
    if (u.getState() != null) claims.claim("state", u.getState());
    if (u.getRecordId() != null) claims.claim("recordId", u.getRecordId());
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }
}
