package org.fpcbazaar.api.forms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.files.FileStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Server-side validation of a form against its schema. Same rules as the frontend's FormEngine, plus
 * length limits, clean-up of control characters and a real file-type check (file content, not the name).
 * Fields that are not in the schema are dropped.
 */
@Component
public class FormValidator {
  private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
  private static final Pattern PHONE_PREFIX = Pattern.compile("^(\\+91|91|0)(?=\\d{10}$)");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
  private static final int TEXT_MAX = 200;
  private static final int AREA_MAX = 3000;
  private static final int EMAIL_MAX = 120;

  private final FileStorage storage;

  public FormValidator(FileStorage storage) { this.storage = storage; }

  public record Result(Map<String, String> values, Map<String, MultipartFile> files) {}

  public Result validate(List<FormField> schema, Map<String, String> input, Map<String, MultipartFile> files) {
    Map<String, String> errors = new LinkedHashMap<>();
    Map<String, String> values = new LinkedHashMap<>();
    Map<String, MultipartFile> okFiles = new LinkedHashMap<>();

    for (String key : files.keySet()) {
      boolean expected = schema.stream().anyMatch((f) -> f.isFile() && f.name().equals(key));
      if (!expected) errors.put(key, "This form does not take a file here.");
    }

    for (FormField f : schema) {
      if (f.isFile()) {
        MultipartFile file = files.get(f.name());
        if (file == null || file.isEmpty()) {
          if (f.required()) errors.put(f.name(), "Please attach this document.");
          continue;
        }
        String problem = storage.check(file);
        if (problem != null) errors.put(f.name(), problem);
        else okFiles.put(f.name(), file);
        continue;
      }
      String v = clean(input.get(f.name()), f.type() == FormField.Type.TEXTAREA);
      if (v.isEmpty()) {
        if (f.required()) errors.put(f.name(), "This field is required.");
        continue;
      }
      String err = null;
      switch (f.type()) {
        case TEL -> {
          String p = PHONE_PREFIX.matcher(v.replaceAll("[\\s-]", "")).replaceFirst("");
          if (!PHONE.matcher(p).matches()) err = "Enter a valid 10-digit mobile number.";
          else v = p;
        }
        case EMAIL -> {
          v = v.toLowerCase(Locale.ROOT);
          if (v.length() > EMAIL_MAX || !EMAIL.matcher(v).matches()) err = "Enter a valid email address.";
        }
        case NUMBER -> {
          try {
            long n = Long.parseLong(v.endsWith(".0") ? v.substring(0, v.length() - 2) : v);
            if (n < f.min() || n > f.max()) err = "Enter a number between " + f.min() + " and " + f.max() + ".";
            else v = Long.toString(n);
          } catch (NumberFormatException e) {
            err = "Enter a number.";
          }
        }
        case SELECT -> {
          if (!f.options().contains(v)) err = "Choose one of the options.";
        }
        case TEXTAREA -> {
          if (v.length() > AREA_MAX) err = "Too long (maximum " + AREA_MAX + " characters).";
        }
        default -> {
          if (v.length() > TEXT_MAX) err = "Too long (maximum " + TEXT_MAX + " characters).";
        }
      }
      if (err != null) errors.put(f.name(), err);
      else values.put(f.name(), normalise(f.name(), v));
    }

    if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Please check the highlighted fields.", errors);
    return new Result(values, okFiles);
  }

  /** CIN, GST and Darpan ID are stored upper-case without spaces so duplicates are found reliably. */
  private static String normalise(String field, String v) {
    if ("cin".equals(field) || "gst".equals(field) || "darpanId".equals(field)) return v.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    return v;
  }

  private static String clean(String raw, boolean multiline) {
    if (raw == null) return "";
    String v = CONTROL.matcher(raw.replace("\r\n", "\n")).replaceAll("");
    if (!multiline) v = v.replace('\n', ' ').replace('\t', ' ');
    return v.strip();
  }
}
