package org.fpcbazaar.api.security;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * Hierarchy: Admin > State Head > Project Head > Vendor > FPO.
 * scope ALL = every record, STATE = records of the user's state, OWN = only the user's own record.
 */
public enum Role {
  ADMIN("admin", Scope.ALL, true),
  STATE_HEAD("state_head", Scope.STATE, true),
  PROJECT_HEAD("project_head", Scope.STATE, false),
  VENDOR("vendor", Scope.OWN, false),
  FPO("fpo", Scope.OWN, false);

  public enum Scope { ALL, STATE, OWN }

  private final String code;
  private final Scope scope;
  private final boolean canDecide;

  Role(String code, Scope scope, boolean canDecide) {
    this.code = code;
    this.scope = scope;
    this.canDecide = canDecide;
  }

  @JsonValue
  public String code() { return code; }
  public Scope scope() { return scope; }
  /** May verify / reject registrations (within its scope). */
  public boolean canDecide() { return canDecide; }

  public static Role fromCode(String code) {
    return Arrays.stream(values()).filter((r) -> r.code.equals(code)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown role " + code));
  }
}
