package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.security.JwtService;
import org.fpcbazaar.api.security.Role;
import org.fpcbazaar.api.users.User;
import org.fpcbazaar.api.users.UserBootstrap;
import org.fpcbazaar.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** ADMIN_RECOVER=true: get back into the admin panel when the old admin's email is lost. */
@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=admin@example.org", "app.admin.password=StrongAdmin123",
    "app.storage.dir=target/test-uploads-recover", "app.rate-limit.login-failures-per-window=1000"
})
@AutoConfigureMockMvc
class AdminRecoverTest {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired MongoTemplate mongo;
  @Autowired AuditService audit;
  @Autowired AppProperties props;
  @Autowired JwtService jwt;

  private void startWith(String username, String password) throws Exception {
    AppProperties p = new AppProperties(props.jwt(), props.cors(), props.storage(),
        new AppProperties.Admin(username, password, "Federation Admin", true), props.publicBaseUrl(), false, props.rateLimit());
    new UserBootstrap(users, encoder, p, mongo, audit).run(null);
  }

  private void login(String u, String p, int expected) throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")).andExpect(status().is(expected));
  }

  private static String email() { return "new" + UUID.randomUUID().toString().substring(0, 8) + "@Example.org"; }

  @Test
  void newEmailBecomesAnAdminAndTheOldAdminIsUntouched() throws Exception {
    String email = email();
    startWith(email, "fpc@123456");
    User u = users.findByUsername(email.toLowerCase()).orElseThrow();
    assertThat(u.getRole()).isEqualTo(Role.ADMIN);
    assertThat(u.isActive()).isTrue();
    assertThat(u.isMustChangePassword()).isFalse();
    login(email.toLowerCase(), "fpc@123456", 200);
    assertThat(users.findByUsername("admin@example.org")).isPresent();
    login("admin@example.org", "StrongAdmin123", 200);
  }

  @Test
  void existingLoginGetsTheNewPasswordAndItsOldSessionsEnd() throws Exception {
    String email = email().toLowerCase();
    users.save(new User(email, encoder.encode("OldPassword123"), Role.PROJECT_HEAD, "Someone", "rajasthan", null));
    String oldToken = "Bearer " + jwt.issue(users.findByUsername(email).orElseThrow());
    startWith(email, "fpc@123456");
    User u = users.findByUsername(email).orElseThrow();
    assertThat(u.getRole()).isEqualTo(Role.ADMIN);
    assertThat(u.getState()).isNull();
    login(email, "OldPassword123", 401);
    login(email, "fpc@123456", 200);
    mvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt.issue(u)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("admin"));
  }

  @Test
  void weakPasswordOrNonEmailChangesNothing() throws Exception {
    String email = email();
    startWith(email, "Fpc@5623");
    assertThat(users.findByUsername(email.toLowerCase())).isEmpty();
    startWith("not-an-email", "fpc@123456");
    assertThat(users.findByUsername("not-an-email")).isEmpty();
  }
}
