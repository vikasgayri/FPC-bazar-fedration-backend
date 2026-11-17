package org.fpcbazaar.api.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Thrown anywhere in the app; turned into {@code { ok:false, message, errors }} by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final Map<String, String> errors;

  public ApiException(HttpStatus status, String message) { this(status, message, Map.of()); }

  public ApiException(HttpStatus status, String message, Map<String, String> errors) {
    super(message);
    this.status = status;
    this.errors = errors == null ? Map.of() : errors;
  }

  public HttpStatus status() { return status; }
  public Map<String, String> errors() { return errors; }

  public static ApiException badRequest(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
  public static ApiException notFound(String msg) { return new ApiException(HttpStatus.NOT_FOUND, msg); }
  public static ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, msg); }
}
