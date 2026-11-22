package org.fpcbazaar.api.notifications;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.mail")
public record MailProperties(@DefaultValue("false") boolean enabled, String apiKey, String from,
    @DefaultValue("FPC Bazaar Federation") String fromName, String encryptionKey,
    @DefaultValue("http://localhost:5173") String frontendUrl,
    @DefaultValue("false") boolean sandbox, @DefaultValue("8") int maxAttempts,
    @DefaultValue("15m") Duration resetTtl) {}
