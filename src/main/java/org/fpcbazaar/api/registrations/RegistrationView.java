package org.fpcbazaar.api.registrations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.users.AccountNotice;

/** Record shape for the admin panel (docs/ADMIN_API.md). {@code files} maps field -> file name only. */
public record RegistrationView(String id, String kind, Status status, String reason, String state, String name, String slug,
    Instant createdAt, Instant decidedAt, String decidedBy, boolean sample, Map<String, String> data,
    Map<String, String> files, List<Registration.HistoryEntry> history, AccountNotice account) {

  public static RegistrationView of(Registration r) { return of(r, null); }

  public static RegistrationView of(Registration r, AccountNotice account) {
    Map<String, String> files = new LinkedHashMap<>();
    r.getFiles().forEach((k, f) -> files.put(k, f.originalName()));
    return new RegistrationView(r.getId(), r.getKind(), r.getStatus(), r.getReason(), r.getState(), r.getName(), r.getSlug(),
        r.getCreatedAt(), r.getDecidedAt(), r.getDecidedBy(), r.isSample(), r.getData(), files, r.getHistory(), account);
  }
}
