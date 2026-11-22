package org.fpcbazaar.api.registrations;

/**
 * Published after a registration is verified / rejected / sent back to pending.
 * The email notification listener queues the appropriate decision email.
 */
public record RegistrationDecidedEvent(String registrationId, String kind, Status from, Status to, String reason,
    String decidedBy) {}
