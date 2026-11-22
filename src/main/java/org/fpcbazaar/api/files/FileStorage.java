package org.fpcbazaar.api.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.fpcbazaar.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Saves uploaded documents under STORAGE_DIR/yyyy/MM/random-id.ext on the server disk.
 * Only PDF, JPG and PNG are accepted, decided from the first bytes of the file (a renamed .exe is refused).
 * File names from the user are kept only as a label; they are never used as a path.
 */
@Component
public class FileStorage {
  private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

  private final Path root;
  private final long maxBytes;
  private final int maxMb;
  private final Clock clock;

  public FileStorage(AppProperties props, Clock clock) {
    AppProperties.Storage s = props.storage();
    this.root = Path.of(s == null ? "./data/uploads" : s.dir()).toAbsolutePath().normalize();
    this.maxMb = s == null ? 5 : s.maxFileMb();
    this.maxBytes = maxMb * 1024L * 1024L;
    this.clock = clock;
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create STORAGE_DIR " + root, e);
    }
    log.info("Uploaded documents are stored in {}", root);
  }

  private enum Kind {
    PDF("application/pdf", "pdf"), JPG("image/jpeg", "jpg"), PNG("image/png", "png");
    final String contentType;
    final String ext;
    Kind(String contentType, String ext) { this.contentType = contentType; this.ext = ext; }
  }

  /** Returns a user-facing problem, or null when the file is acceptable. */
  public String check(MultipartFile file) {
    if (file.getSize() > maxBytes) return "File is too large. Maximum " + maxMb + " MB.";
    if (detect(file) == null) return "Only PDF, JPG or PNG files are allowed.";
    return null;
  }

  public StoredFile store(MultipartFile file) {
    Kind kind = detect(file);
    if (kind == null || file.getSize() > maxBytes) throw new IllegalArgumentException("File was not checked before storing");
    LocalDate d = LocalDate.now(clock);
    String rel = String.format("%d/%02d/%s.%s", d.getYear(), d.getMonthValue(), UUID.randomUUID(), kind.ext);
    Path target = resolve(rel);
    try {
      Files.createDirectories(target.getParent());
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(file.getInputStream(), sha)) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return new StoredFile(label(file.getOriginalFilename(), kind), rel, kind.contentType, Files.size(target),
          HexFormat.of().formatHex(sha.digest()));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not save uploaded file", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Absolute path of a stored file; refuses anything outside STORAGE_DIR. */
  public Path resolve(String rel) {
    Path p = root.resolve(rel).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("Path outside storage");
    return p;
  }

  public void deleteQuietly(StoredFile f) {
    if (f == null) return;
    try {
      Files.deleteIfExists(resolve(f.path()));
    } catch (IOException | IllegalArgumentException e) {
      log.warn("Could not delete {}", f.path());
    }
  }

  private static Kind detect(MultipartFile file) {
    byte[] h = new byte[8];
    int n;
    try (InputStream in = file.getInputStream()) {
      n = in.readNBytes(h, 0, h.length);
    } catch (IOException e) {
      return null;
    }
    if (n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-') return Kind.PDF;
    if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return Kind.JPG;
    if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D && h[5] == 0x0A
        && h[6] == 0x1A && h[7] == 0x0A) return Kind.PNG;
    return null;
  }

  /** Safe display name: no folders, no odd characters, correct extension. */
  private static String label(String original, Kind kind) {
    String base = original == null ? "" : original.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    base = base.replaceAll("[^A-Za-z0-9 ._()-]", "").strip();
    if (base.isEmpty()) base = "document";
    if (base.length() > 80) base = base.substring(0, 80);
    return base + "." + kind.ext;
  }
}
