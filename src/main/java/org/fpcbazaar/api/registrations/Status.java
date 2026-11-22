package org.fpcbazaar.api.registrations;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/** Review status of a registration. Only VERIFIED records are ever shown on the public site. */
public enum Status {
  PENDING, VERIFIED, REJECTED;

  @JsonValue
  public String code() { return name().toLowerCase(Locale.ROOT); }

  /** Returns null for unknown values. */
  public static Status fromCode(String code) {
    if (code == null) return null;
    return Arrays.stream(values()).filter((s) -> s.code().equals(code.trim().toLowerCase(Locale.ROOT))).findFirst().orElse(null);
  }
}
