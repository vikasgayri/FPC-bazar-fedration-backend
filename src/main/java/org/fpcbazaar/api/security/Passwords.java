package org.fpcbazaar.api.security;

import java.security.SecureRandom;

/** Password rules and one-time passwords for new logins / resets. */
public final class Passwords {
  private Passwords() {}

  private static final SecureRandom RANDOM = new SecureRandom();
  /** No 0/O, 1/l/I so it can be read out on the phone. */
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  public static String temporary() {
    StringBuilder b = new StringBuilder(12);
    while (true) {
      b.setLength(0);
      for (int i = 0; i < 12; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      String s = b.toString();
      if (s.chars().anyMatch(Character::isDigit) && s.chars().anyMatch(Character::isLetter)) return s;
    }
  }

  public static final int MAX_BYTES = 72;

  /** Returns a user-facing problem or null. */
  public static String problem(String password, String username) {
    if (password == null || password.length() < 10) return "Password must be at least 10 characters.";
    // BCrypt uses only the first 72 bytes (Hindi letters take 3 bytes each); longer passwords are refused.
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
      return "Password is too long (maximum 72 English characters, fewer in Hindi).";
    if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit))
      return "Use letters and numbers.";
    if (username != null && password.equalsIgnoreCase(username)) return "Password cannot be the same as the username.";
    return null;
  }
}
