package org.fpcbazaar.api.notifications;

/** Never log this object: account emails contain a one-time credential. */
public record MailPayload(String to, String subject, String text) {
  @Override public String toString() { return "MailPayload[redacted]"; }
}
