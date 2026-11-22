package org.fpcbazaar.api.notifications;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** AES-GCM protects queued credentials and recipient data at rest; the key stays outside MongoDB. */
@Component
public class MailCipher {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();
  private final JsonMapper json = JsonMapper.builder().build();

  public MailCipher(MailProperties props) {
    byte[] decoded = new byte[0];
    try {
      if (props.encryptionKey() != null && !props.encryptionKey().isBlank())
        decoded = Base64.getDecoder().decode(props.encryptionKey());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("MAIL_ENCRYPTION_KEY must be base64 encoding of 32 random bytes");
    }
    if (props.enabled() && decoded.length != 32)
      throw new IllegalStateException("Set MAIL_ENCRYPTION_KEY to base64 encoding of 32 random bytes before enabling email");
    key = decoded;
  }

  public String encrypt(MailPayload payload) {
    try {
      byte[] iv = new byte[12]; random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
    } catch (Exception e) { throw new IllegalStateException("Cannot encrypt mail payload"); }
  }

  public MailPayload decrypt(String value) {
    try {
      String[] parts = value.split("\\.", 2);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      return json.readValue(cipher.doFinal(Base64.getDecoder().decode(parts[1])), MailPayload.class);
    } catch (Exception e) { throw new IllegalStateException("Cannot decrypt mail payload"); }
  }
}
