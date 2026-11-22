package org.fpcbazaar.api.users;

/**
 * Result of creating a vendor / FPO login when a registration is verified.
 * {@code tempPassword} is shown once to the admin (Step 4 also queues it by email).
 */
public record AccountNotice(boolean created, String username, String tempPassword, String message) {}
