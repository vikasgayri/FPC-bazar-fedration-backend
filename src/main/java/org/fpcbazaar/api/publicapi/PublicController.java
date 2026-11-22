package org.fpcbazaar.api.publicapi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.files.FileStorage;
import org.fpcbazaar.api.files.StoredFile;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.registrations.Status;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public website data. Only VERIFIED records are ever returned here; pending and rejected ones
 * exist only in the admin panel. Private fields (GST, turnover, CIN, email, documents) are never exposed.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {
  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();
  private static final List<String> VENDOR_PUBLIC_FIELDS =
      List.of("district", "productCategory", "supplyState", "about", "productList", "pastWork", "contact", "phone");

  private final RegistrationRepository repo;
  private final FileStorage storage;
  private final String baseUrl;

  public PublicController(RegistrationRepository repo, FileStorage storage, AppProperties props) {
    this.repo = repo;
    this.storage = storage;
    this.baseUrl = props.publicBaseUrl().replaceAll("/+$", "");
  }

  @GetMapping("/vendors")
  public ResponseEntity<Map<String, Object>> vendors() {
    List<Map<String, Object>> items = repo.findByKindAndStatusOrderByNameAsc("vendor", Status.VERIFIED).stream()
        .map(this::vendor).toList();
    return ResponseEntity.ok().cacheControl(CACHE).body(Map.of("items", items));
  }

  @GetMapping("/vendors/{slug}")
  public ResponseEntity<Map<String, Object>> vendor(@PathVariable String slug) {
    return ResponseEntity.ok().cacheControl(CACHE).body(vendor(verifiedVendor(slug)));
  }

  @GetMapping("/vendors/{slug}/logo")
  public ResponseEntity<Resource> logo(@PathVariable String slug) {
    StoredFile f = verifiedVendor(slug).getFiles().get("logo");
    if (f == null || !f.contentType().startsWith("image/")) throw ApiException.notFound("Not found.");
    Path p = storage.resolve(f.path());
    if (!Files.isRegularFile(p)) throw ApiException.notFound("Not found.");
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(new FileSystemResource(p));
  }

  @GetMapping("/fpos")
  public ResponseEntity<Map<String, Object>> fpos() {
    List<Map<String, Object>> items = repo.findByKindAndStatusOrderByNameAsc("fpo", Status.VERIFIED).stream().map((r) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", r.getId());
      m.put("name", r.getName());
      m.put("state", r.getState());
      m.put("district", r.getData().get("district"));
      m.put("kisanCount", r.getData().get("kisanCount"));
      m.put("odop", r.getData().get("odop"));
      m.put("sample", r.isSample());
      return m;
    }).toList();
    return ResponseEntity.ok().cacheControl(CACHE).body(Map.of("items", items));
  }

  private Registration verifiedVendor(String slug) {
    return repo.findBySlugAndKindAndStatus(slug, "vendor", Status.VERIFIED)
        .orElseThrow(() -> ApiException.notFound("Not found."));
  }

  /** Shape read by the frontend's toSupplier() in src/services/registry.js. */
  private Map<String, Object> vendor(Registration r) {
    Map<String, String> data = new LinkedHashMap<>();
    VENDOR_PUBLIC_FIELDS.forEach((k) -> {
      String v = r.getData().get(k);
      if (v != null) data.put(k, v);
    });
    Map<String, String> files = new LinkedHashMap<>();
    StoredFile cert = r.getFiles().get("certificate");
    if (cert != null) files.put("certificate", cert.originalName());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", "vendor");
    m.put("slug", r.getSlug());
    m.put("name", r.getName());
    m.put("state", r.getState());
    m.put("sample", r.isSample());
    if (r.getFiles().containsKey("logo")) m.put("logoUrl", baseUrl + "/api/public/vendors/" + r.getSlug() + "/logo");
    m.put("data", data);
    m.put("files", files);
    return m;
  }
}
