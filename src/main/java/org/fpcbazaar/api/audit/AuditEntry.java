package org.fpcbazaar.api.audit;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Who did what and when: logins, user changes, verify/reject, exports. Never edited or deleted by the app. */
@Document("audit_log")
public record AuditEntry(@Id String id, @Indexed Instant at, String actorId, String actorName, String actorRole,
    @Indexed String action, String targetType, String targetId, String summary, String ip) {}
