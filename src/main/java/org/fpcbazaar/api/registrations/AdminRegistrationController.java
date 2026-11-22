package org.fpcbazaar.api.registrations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.files.FileStorage;
import org.fpcbazaar.api.files.StoredFile;
import org.fpcbazaar.api.security.Auth;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin panel: list, review (verify / reject with reason) and open documents. Role scope applies to every call. */
@RestController
@RequestMapping("/api/admin/registrations")
public class AdminRegistrationController {
  private final RegistrationService service;
  private final FileStorage storage;

  public AdminRegistrationController(RegistrationService service, FileStorage storage) {
    this.service = service;
    this.storage = storage;
  }

  public record DecideRequest(String status, String reason) {}

  @GetMapping
  public Map<String, List<RegistrationView>> list(@RequestParam(required = false) String kind,
      @RequestParam(required = false) String status, @RequestParam(required = false) String state,
      @RequestParam(required = false) String q) {
    return Map.of("items", service.list(Auth.current(), kind, status, state, q).stream().map(RegistrationView::of).toList());
  }

  @GetMapping("/{id}")
  public RegistrationView one(@PathVariable String id) {
    return RegistrationView.of(service.get(Auth.current(), id));
  }

  @PatchMapping("/{id}")
  public RegistrationView decide(@PathVariable String id, @RequestBody DecideRequest body) {
    if (body == null) throw ApiException.badRequest("The request could not be read.");
    RegistrationService.Decision d = service.decide(Auth.current(), id, body.status(), body.reason());
    return RegistrationView.of(d.registration(), d.account());
  }

  @GetMapping("/{id}/files/{key}")
  public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String key) {
    StoredFile f = service.file(Auth.current(), id, key);
    Path p = storage.resolve(f.path());
    if (!Files.isRegularFile(p)) throw ApiException.notFound("Document not found.");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(f.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.originalName(), StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore().cachePrivate())
        .body(new FileSystemResource(p));
  }
}
