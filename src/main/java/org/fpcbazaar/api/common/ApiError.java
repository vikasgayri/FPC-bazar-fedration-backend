package org.fpcbazaar.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Single error shape for every failure. The frontend shows {@code message}; forms map {@code errors} to fields. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(boolean ok, String message, Map<String, String> errors) {
  public static ApiError of(String message, Map<String, String> errors) { return new ApiError(false, message, errors); }
}
