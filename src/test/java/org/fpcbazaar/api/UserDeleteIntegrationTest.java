package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.fpcbazaar.api.audit.AuditEntry;
import org.fpcbazaar.api.files.FileStorage;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.security.JwtService;
import org.fpcbazaar.api.security.Role;
import org.fpcbazaar.api.users.User;
import org.fpcbazaar.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Admin deletes a login permanently; for a vendor / FPO also the application and its documents. */
@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=admin@example.org", "app.admin.password=StrongAdmin123",
    "app.storage.dir=target/test-uploads-delete", "app.rate-limit.forms-per-minute=1000",
    "app.rate-limit.login-failures-per-window=1000"
})
@AutoConfigureMockMvc
class UserDeleteIntegrationTest {
  static final byte[] PDF = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

  @Autowired MockMvc mvc;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired RegistrationRepository registrations;
  @Autowired PasswordEncoder encoder;
  @Autowired FileStorage storage;
  @Autowired JwtService jwt;

  private String bearer(String username) { return "Bearer " + jwt.issue(users.findByUsername(username).orElseThrow()); }
  private String admin() { return bearer("admin@example.org"); }
  private static String rnd() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8); }

  private String user(Role role, String state) {
    String email = role.code().replace("_", "") + rnd() + "@example.org";
    users.save(new User(email, encoder.encode("GoodPassword123"), role, "Person " + role.code(), state, null));
    return email;
  }

  @Test
  void deletingAVendorRemovesLoginApplicationDocumentsAndPublicPage() throws Exception {
    String email = "vendor" + rnd() + "@example.org";
    String id = JsonPath.read(mvc.perform(multipart("/api/forms/vendor")
            .file(new MockMultipartFile("certificate", "c.pdf", "application/pdf", PDF))
            .file(new MockMultipartFile("logo", "logo.png", "image/png", PNG))
            .param("companyName", "Delete Me Spices " + rnd()).param("gst", "08ABCDE" + (1000 + new java.util.Random().nextInt(9000)) + "F1Z5")
            .param("turnover", "1cr").param("productCategory", "spices").param("supplyState", "rajasthan").param("state", "rajasthan")
            .param("name", "Asha").param("phone", "9876543210").param("email", email).param("consent", "true")
            .param("password", "VendorPass2026").param("passwordConfirm", "VendorPass2026"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    mongo.updateFirst(Query.query(Criteria.where("username").is(email)),
        new org.springframework.data.mongodb.core.query.Update().set("emailUnconfirmed", false), User.class);
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"verified\"}")).andExpect(status().isOk());
    Registration r = registrations.findById(id).orElseThrow();
    List<Path> paths = r.getFiles().values().stream().map((f) -> storage.resolve(f.path())).toList();
    assertThat(paths).hasSize(2).allMatch(Files::exists);
    mvc.perform(get("/api/public/vendors/" + r.getSlug())).andExpect(status().isOk());
    String vendorToken = bearer(email);
    User u = users.findByUsername(email).orElseThrow();

    mvc.perform(delete("/api/admin/users/" + u.getId()).header("Authorization", admin()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(email))
        .andExpect(jsonPath("$.applicationDeleted").value(true)).andExpect(jsonPath("$.filesDeleted").value(2));

    assertThat(users.findByUsername(email)).isEmpty();
    assertThat(registrations.findById(id)).isEmpty();
    assertThat(paths).noneMatch(Files::exists);
    mvc.perform(get("/api/public/vendors/" + r.getSlug())).andExpect(status().isNotFound());
    mvc.perform(get("/api/auth/me").header("Authorization", vendorToken)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + email + "\",\"password\":\"VendorPass2026\"}")).andExpect(status().isUnauthorized());
    AuditEntry log = mongo.findOne(Query.query(Criteria.where("action").is("user_deleted").and("targetId").is(u.getId())), AuditEntry.class);
    assertThat(log).isNotNull();
    assertThat(log.toString()).contains(email).contains(id);
    mvc.perform(delete("/api/admin/users/" + u.getId()).header("Authorization", admin())).andExpect(status().isNotFound());
  }

  @Test
  void deletingStaffRemovesOnlyTheLogin() throws Exception {
    String head = user(Role.STATE_HEAD, "rajasthan");
    long registrationsBefore = registrations.count();
    String id = users.findByUsername(head).orElseThrow().getId();
    mvc.perform(delete("/api/admin/users/" + id).header("Authorization", admin()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.applicationDeleted").value(false)).andExpect(jsonPath("$.filesDeleted").value(0));
    assertThat(users.findById(id)).isEmpty();
    assertThat(registrations.count()).isEqualTo(registrationsBefore);
  }

  @Test
  void onlyAdminDeletesAndNeverTheirOwnLogin() throws Exception {
    String head = user(Role.STATE_HEAD, "rajasthan");
    String project = user(Role.PROJECT_HEAD, "rajasthan");
    String projectId = users.findByUsername(project).orElseThrow().getId();
    mvc.perform(delete("/api/admin/users/" + projectId).header("Authorization", bearer(head))).andExpect(status().isForbidden());
    mvc.perform(delete("/api/admin/users/" + projectId).header("Authorization", bearer(project))).andExpect(status().isForbidden());
    assertThat(users.findById(projectId)).isPresent();

    String adminId = users.findByUsername("admin@example.org").orElseThrow().getId();
    mvc.perform(delete("/api/admin/users/" + adminId).header("Authorization", admin())).andExpect(status().isBadRequest());

    String second = user(Role.ADMIN, null);
    String secondId = users.findByUsername(second).orElseThrow().getId();
    mvc.perform(delete("/api/admin/users/" + secondId).header("Authorization", admin())).andExpect(status().isOk());
    assertThat(users.findByUsername("admin@example.org")).isPresent();
  }
}
