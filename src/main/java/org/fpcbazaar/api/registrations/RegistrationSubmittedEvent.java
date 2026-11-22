package org.fpcbazaar.api.registrations;

/** Published after a new registration is saved (Step 4: "we received your application" email). */
public record RegistrationSubmittedEvent(String registrationId, String kind, String state) {}
