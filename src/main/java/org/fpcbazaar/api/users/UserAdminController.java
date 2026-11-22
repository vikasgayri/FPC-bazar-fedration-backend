package org.fpcbazaar.api.users;

import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.security.Auth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin panel "Users": create State Head / Project Head / vendor / FPO logins, switch them off, reset passwords. */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {
  private final UserAdminService service;

  public UserAdminController(UserAdminService service) { this.service = service; }

  @GetMapping
  public Map<String, List<UserView>> list(@RequestParam(required = false) String role,
      @RequestParam(required = false) String state, @RequestParam(required = false) String q) {
    return Map.of("items", service.list(Auth.current(), role, state, q).stream().map(UserView::of).toList());
  }

  @PostMapping
  public ResponseEntity<UserAdminService.Issued> create(@RequestBody UserAdminService.CreateRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(Auth.current(), body));
  }

  @PatchMapping("/{id}")
  public UserView update(@PathVariable String id, @RequestBody UserAdminService.UpdateRequest body) {
    return UserView.of(service.update(Auth.current(), id, body));
  }

  @PostMapping("/{id}/reset-password")
  public UserAdminService.Issued reset(@PathVariable String id) {
    return service.resetPassword(Auth.current(), id);
  }
}
