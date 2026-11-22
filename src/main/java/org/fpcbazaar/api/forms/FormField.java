package org.fpcbazaar.api.forms;

import java.util.Set;

/**
 * One field of a public form. Mirrors {@code src/config/forms.js} in the frontend
 * (n = name, t = type, r = required, o = options, min / max for numbers).
 */
public record FormField(String name, Type type, boolean required, Set<String> options, long min, long max) {

  public enum Type { TEXT, TEL, EMAIL, NUMBER, SELECT, TEXTAREA, FILE }

  public static FormField text(String n, boolean r) { return new FormField(n, Type.TEXT, r, null, 0, 0); }
  public static FormField tel(String n, boolean r) { return new FormField(n, Type.TEL, r, null, 0, 0); }
  public static FormField email(String n, boolean r) { return new FormField(n, Type.EMAIL, r, null, 0, 0); }
  public static FormField area(String n, boolean r) { return new FormField(n, Type.TEXTAREA, r, null, 0, 0); }
  public static FormField file(String n, boolean r) { return new FormField(n, Type.FILE, r, null, 0, 0); }
  public static FormField number(String n, boolean r, long min, long max) { return new FormField(n, Type.NUMBER, r, null, min, max); }
  public static FormField select(String n, boolean r, Set<String> options) { return new FormField(n, Type.SELECT, r, options, 0, 0); }

  public boolean isFile() { return type == Type.FILE; }
}
