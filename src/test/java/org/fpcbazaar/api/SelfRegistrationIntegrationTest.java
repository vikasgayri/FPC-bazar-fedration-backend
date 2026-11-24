package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.fpcbazaar.api.notifications.BrevoTransport;
import org.fpcbazaar.api.notifications.MailCipher;
import org.fpcbazaar.api.notifications.MailJob;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.security.JwtService;
import org.fpcbazaar.api.users.User;
import org.fpcbazaar.api.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Self-registration (FPO / vendor with their own password), email confirmation and "Track your application". */
@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=admin@example.org", "app.admin.password=StrongAdmin123",
    "app.storage.dir=target/test-uploads-step4", "app.rate-limit.forms-per-minute=1000",
    "app.rate-limit.login-failures-per-window=1000", "app.mail.enabled=true",
    "app.mail.api-key=test-only", "app.mail.from=sender@example.org",
    "app.mail.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.mail.frontend-url=https://frontend.example.org", "app.mail.initial-delay=86400000"
})
@AutoConfigureMockMvc
class SelfRegistrationIntegrationTest {
  static final byte[] PDF = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);

  @Autowired MockMvc mvc;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired RegistrationRepository registrations;
  @Autowired MailCipher cipher;
  @Autowired JwtService jwt;
  @MockitoBean BrevoTransport transport;

  @BeforeEach
  void clean() {
    mongo.remove(new Query(), MailJob.class);
    reset(transport);
    when(transport.send(any())).thenReturn("<test@brevo>");
  }

  private static String rnd() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

  private MockMultipartHttpServletRequestBuilder fpo(String email, String password, String confirm) {
    MockMultipartHttpServletRequestBuilder b = multipart("/api/forms/fpo")
        .file(new MockMultipartFile("certificate", "c.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("pan", "p.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("boardResolution", "b.pdf", "application/pdf", PDF));
    b.param("fpoName", "Self FPO " + rnd()).param("state", "rajasthan").param("district", "Kota")
        .param("cin", "U01100RJ2024PTC" + rnd().replaceAll("[a-f]", "1").substring(0, 6)).param("kisanCount", "120")
        .param("name", "Hari").param("phone", "9876501234").param("email", email).param("consent", "true").param("lang", "en");
    if (password != null) b.param("password", password);
    if (confirm != null) b.param("passwordConfirm", confirm);
    return b;
  }

  private ResultActions login(String u, String p) throws Exception {
    return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"));
  }

  private String json(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

  private List<MailJob> jobs(String kind) {
    return mongo.find(Query.query(Criteria.where("kind").is(kind)), MailJob.class);
  }

  private String linkToken(String path) {
    MailJob job = jobs("email_confirmation").getLast();
    String text = cipher.decrypt(job.payload).text();
    return text.substring(text.indexOf(path + "#token=") + path.length() + 7).split("\\s")[0];
  }

  private ResultActions confirm(String token) throws Exception {
    return mvc.perform(post("/api/auth/confirm-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
  }

  private String admin() { return "Bearer " + jwt.issue(users.findByUsername("admin@example.org").orElseThrow()); }

  @Test
  void fpoChoosesPasswordConfirmsEmailThenSeesOnlyOwnPendingRecord() throws Exception {
    String email = "self" + rnd() + "@example.org";
    String id = JsonPath.read(json(mvc.perform(fpo(email, "MyKisanPass2026", "MyKisanPass2026")).andExpect(status().isCreated())
        .andExpect(jsonPath("$.login").value("confirm_email"))), "$.id");

    // the password is never kept with the form
    assertThat(registrations.findById(id).orElseThrow().getData()).doesNotContainKeys("password", "passwordConfirm");
    User u = users.findByUsername(email).orElseThrow();
    assertThat(u.getPasswordHash()).startsWith("$2").doesNotContain("MyKisanPass2026");
    assertThat(u.getRecordId()).isEqualTo(id);
    assertThat(u.isEmailUnconfirmed()).isTrue();
    assertThat(jobs("email_confirmation")).hasSize(1);

    login(email, "wrong-pass-123").andExpect(status().isUnauthorized());
    login(email, "MyKisanPass2026").andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.email").value("email_unconfirmed"));

    String token = linkToken("/confirm-email");
    confirm(token).andExpect(status().isOk());
    confirm(token).andExpect(status().isBadRequest()); // one use only

    String session = JsonPath.read(json(login(email, "MyKisanPass2026").andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("fpo")).andExpect(jsonPath("$.recordId").value(id))
        .andExpect(jsonPath("$.mustChangePassword").value(false))), "$.token");
    mvc.perform(get("/api/admin/registrations").header("Authorization", "Bearer " + session))
        .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].status").value("pending"));

    // the Federation verifies later: no second login, no one-time password
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"verified\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.account.created").value(false))
        .andExpect(jsonPath("$.account.tempPassword").doesNotExist());
    assertThat(jobs("account_created")).isEmpty();
    assertThat(mongo.count(Query.query(Criteria.where("recordId").is(id)), User.class)).isEqualTo(1);
  }

  @Test
  void badPasswordsAndTakenEmailsAreRefusedBeforeAnythingIsSaved() throws Exception {
    long before = registrations.count();
    mvc.perform(fpo("weak" + rnd() + "@example.org", "short1", null)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.password").exists());
    mvc.perform(fpo("mismatch" + rnd() + "@example.org", "GoodPassword123", "GoodPassword124")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.passwordConfirm").exists());
    mvc.perform(fpo("admin@example.org", "GoodPassword123", "GoodPassword123")).andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.email").exists());
    assertThat(registrations.count()).isEqualTo(before);
  }

  @Test
  void withoutPasswordTheFormWorksAsBeforeAndOtherFormsIgnoreIt() throws Exception {
    String email = "plain" + rnd() + "@example.org";
    mvc.perform(fpo(email, null, null)).andExpect(status().isCreated()).andExpect(jsonPath("$.login").doesNotExist());
    assertThat(users.findByUsername(email)).isEmpty();

    String other = "member" + rnd() + "@example.org";
    mvc.perform(multipart("/api/forms/member").file(new MockMultipartFile("feeSlip", "f.pdf", "application/pdf", PDF))
            .param("name", "M").param("phone", "9876501234").param("email", other).param("state", "goa")
            .param("password", "GoodPassword123").param("consent", "true"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.login").doesNotExist());
    assertThat(users.findByUsername(other)).isEmpty();
  }

  @Test
  void aNewLinkReplacesTheOldOneAndAPasswordResetAlsoConfirms() throws Exception {
    String email = "resend" + rnd() + "@example.org";
    mvc.perform(fpo(email, "ResendPass2026", null)).andExpect(status().isCreated());
    String first = linkToken("/confirm-email");

    mvc.perform(post("/api/auth/resend-confirmation").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody@example.org\"}"))
        .andExpect(status().isOk());
    assertThat(jobs("email_confirmation")).hasSize(1); // unknown login: same answer, nothing sent
    mvc.perform(post("/api/auth/resend-confirmation").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + email + "\"}"))
        .andExpect(status().isOk());
    assertThat(jobs("email_confirmation")).hasSize(2);
    confirm(first).andExpect(status().isBadRequest()); // the old link no longer works

    // "Forgot password" goes to the same email, so finishing it also confirms the email
    mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + email + "\"}"))
        .andExpect(status().isOk());
    String text = cipher.decrypt(jobs("password_reset_link").getLast().payload).text();
    String reset = text.substring(text.indexOf("#token=") + 7).split("\\s")[0];
    mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + reset + "\",\"newPassword\":\"BrandNewPass2026\"}")).andExpect(status().isOk());
    login(email, "BrandNewPass2026").andExpect(status().isOk());
  }

  @Test
  void confirmPageIsPublicAndKeepsTheTokenOutOfTheAddress() throws Exception {
    mvc.perform(get("/confirm-email")).andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")));
    mvc.perform(get("/confirm-email.js")).andExpect(status().isOk());
  }

  @Test
  void trackNeedsReferenceAndMobileAndIsRateLimited() throws Exception {
    String id = JsonPath.read(json(mvc.perform(fpo("track" + rnd() + "@example.org", null, null)).andExpect(status().isCreated())), "$.id");
    ResultActions ok = mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON)
        .content("{\"reference\":\" " + id.toUpperCase() + " \",\"phone\":\"+91 98765-01234\"}"));
    ok.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending")).andExpect(jsonPath("$.kind").value("fpo"))
        .andExpect(jsonPath("$.reason").doesNotExist()).andExpect(jsonPath("$.data").doesNotExist());

    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"rejected\",\"reason\":\"Board resolution unsigned\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reference\":\"" + id + "\",\"phone\":\"9876501234\"}"))
        .andExpect(jsonPath("$.status").value("rejected")).andExpect(jsonPath("$.reason").value("Board resolution unsigned"));

    // wrong mobile, malformed and unknown references all get the same answer
    for (String body : new String[] {"{\"reference\":\"" + id + "\",\"phone\":\"9999999999\"}",
        "{\"reference\":\"../etc\",\"phone\":\"9876501234\"}", "{\"reference\":\"0123456789abcdef01234567\",\"phone\":\"9876501234\"}"})
      mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("No application found")));

    // correct look-ups never use up the limit (one office IP can keep checking)
    for (int i = 0; i < 30; i++)
      mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON)
          .content("{\"reference\":\"" + id + "\",\"phone\":\"9876501234\"}")).andExpect(status().isOk());

    int status = 0, wrong = 3; // three wrong tries above
    while (status != 429 && wrong < 40) {
      status = mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON)
          .content("{\"reference\":\"" + id + "\",\"phone\":\"9999999999\"}")).andReturn().getResponse().getStatus();
      if (status != 429) wrong++;
    }
    assertThat(wrong).isEqualTo(20); // 20 wrong tries per 15 minutes from one connection
    // once blocked, even the right pair waits (no free oracle while blocked)
    mvc.perform(post("/api/public/track").contentType(MediaType.APPLICATION_JSON)
        .content("{\"reference\":\"" + id + "\",\"phone\":\"9876501234\"}")).andExpect(status().isTooManyRequests());
  }
}
