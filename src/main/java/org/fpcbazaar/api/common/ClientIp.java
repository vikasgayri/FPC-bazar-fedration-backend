package org.fpcbazaar.api.common;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Client address for rate limiting. Behind the Infinitive Cloud reverse proxy (nginx) set
 * {@code server.forward-headers-strategy=native} so getRemoteAddr() already returns the real client.
 */
public final class ClientIp {
  private ClientIp() {}

  public static String of(HttpServletRequest req) {
    String ip = req.getRemoteAddr();
    return ip == null ? "unknown" : ip;
  }
}
