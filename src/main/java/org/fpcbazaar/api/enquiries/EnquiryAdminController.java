package org.fpcbazaar.api.enquiries;

import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.security.Auth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin panel "Enquiries" inbox. */
@RestController
@RequestMapping("/api/admin/enquiries")
public class EnquiryAdminController {
  private final EnquiryService service;

  public EnquiryAdminController(EnquiryService service) { this.service = service; }

  @GetMapping
  public Map<String, List<Enquiry>> list(@RequestParam(required = false) String formKey,
      @RequestParam(required = false) String status, @RequestParam(required = false) String q,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return Map.of("items", service.list(Auth.current(), formKey, status, q, from, to, 500));
  }

  @GetMapping("/{id}")
  public Enquiry one(@PathVariable String id) { return service.get(Auth.current(), id); }

  @PatchMapping("/{id}")
  public Enquiry update(@PathVariable String id, @RequestBody EnquiryService.UpdateRequest body) {
    return service.update(Auth.current(), id, body);
  }
}
