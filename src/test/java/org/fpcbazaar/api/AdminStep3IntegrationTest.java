package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 3: logins, password change, NGO form, enquiries inbox, Excel export, dashboard numbers, activity log. */
@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=chairman",
    "app.admin.password=very-strong-pass",
    "app.storage.dir=target/test-uploads-reg",
    "app.seed-demo=true",
    "app.public-base-url=https://api.example.org",
    "app.rate-limit.forms-per-minute=1000",
    "app.rate-limit.login-failures-per-window=50" // many logins from one test IP; the limit itself is tested in AuthIntegrationTest
})
@AutoConfigureMockMvc
class AdminStep3IntegrationTest {
  static final byte[] PDF = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);

  @Autowired MockMvc mvc;

  private static String rnd() { return Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999)); }

  private ResultActions loginCall(String u, String p) throws Exception {
    return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"));
  }

  private String login(String u, String p) throws Exception {
    return JsonPath.read(loginCall(u, p).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
  }

  private static String auth(String t) { return "Bearer " + t; }

  private String json(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

  private String changePassword(String token, String current, String next) throws Exception {
    return JsonPath.read(json(mvc.perform(post("/api/auth/change-password").header("Authorization", auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(false))), "$.token");
  }

  @Test
  void stateHeadLifecycleAndPasswordRules() throws Exception {
    String admin = login("chairman", "very-strong-pass");
    String username = "gj.head" + rnd() + "@example.com";

    String created = json(mvc.perform(post("/api/admin/users").header("Authorization", auth(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username.toUpperCase() + "\",\"name\":\"Gujarat Head\",\"role\":\"state_head\",\"state\":\"gujarat\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.username").value(username))
        .andExpect(jsonPath("$.user.mustChangePassword").value(true))
        .andExpect(jsonPath("$.user.passwordHash").doesNotExist()));
    String id = JsonPath.read(created, "$.user.id");
    String temp = JsonPath.read(created, "$.tempPassword");

    // duplicate username, missing state, bad role
    mvc.perform(post("/api/admin/users").header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"name\":\"X\",\"role\":\"state_head\",\"state\":\"goa\"}"))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/admin/users").header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"x" + rnd() + "\",\"name\":\"X\",\"role\":\"project_head\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.state").exists());
    mvc.perform(post("/api/admin/users").header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"x" + rnd() + "\",\"name\":\"X\",\"role\":\"king\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.role").exists());

    // first sign-in: must change password before anything else
    String first = JsonPath.read(json(loginCall(username, temp).andExpect(jsonPath("$.mustChangePassword").value(true))), "$.token");
    mvc.perform(get("/api/admin/registrations").header("Authorization", auth(first)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("password_change_required"));
    mvc.perform(get("/api/auth/me").header("Authorization", auth(first)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
    mvc.perform(post("/api/auth/change-password").header("Authorization", auth(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong-one-123\",\"newPassword\":\"gujarat-head-2026\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.currentPassword").exists());
    mvc.perform(post("/api/auth/change-password").header("Authorization", auth(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"onlyletters\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.newPassword").exists());
    String head = changePassword(first, temp, "gujarat-head-2026");
    mvc.perform(get("/api/admin/registrations").header("Authorization", auth(first))).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/registrations").header("Authorization", auth(head)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].state", everyItem(is("gujarat"))));

    // State Head manages Project Heads of their own state only
    mvc.perform(post("/api/admin/users").header("Authorization", auth(head)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ph" + rnd() + "\",\"name\":\"PH Gujarat\",\"role\":\"project_head\",\"state\":\"gujarat\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/admin/users").header("Authorization", auth(head)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ph" + rnd() + "\",\"name\":\"PH Raj\",\"role\":\"project_head\",\"state\":\"rajasthan\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/users").header("Authorization", auth(head)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"sh" + rnd() + "\",\"name\":\"Another head\",\"role\":\"state_head\",\"state\":\"gujarat\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").header("Authorization", auth(head)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].role", everyItem(is("project_head"))))
        .andExpect(jsonPath("$.items[*].state", everyItem(is("gujarat"))));
    String projectHead = login("project-head", "project123");
    mvc.perform(get("/api/admin/users").header("Authorization", auth(projectHead))).andExpect(status().isForbidden());

    // switch off: the token stops working at once and sign-in is refused
    mvc.perform(patch("/api/admin/users/" + id).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"active\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    mvc.perform(get("/api/admin/registrations").header("Authorization", auth(head))).andExpect(status().isUnauthorized());
    loginCall(username, "gujarat-head-2026").andExpect(status().isUnauthorized());

    // switch on + reset: new one-time password
    mvc.perform(patch("/api/admin/users/" + id).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"active\":true}")).andExpect(status().isOk());
    String reset = JsonPath.read(json(mvc.perform(post("/api/admin/users/" + id + "/reset-password")
        .header("Authorization", auth(admin))).andExpect(status().isOk())), "$.tempPassword");
    loginCall(username, "gujarat-head-2026").andExpect(status().isUnauthorized());
    loginCall(username, reset).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));

    // the last admin cannot be switched off, nobody can switch off themselves
    String adminId = JsonPath.read(json(mvc.perform(get("/api/admin/users").param("role", "admin").param("q", "chairman")
        .header("Authorization", auth(admin)))), "$.items[0].id");
    mvc.perform(patch("/api/admin/users/" + adminId).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"active\":false}")).andExpect(status().isBadRequest());
  }

  @Test
  void verifiedFpoGetsAnEmailLoginThatSeesOnlyItsRecord() throws Exception {
    String email = "kisan" + rnd() + "@example.com";
    String id = JsonPath.read(json(mvc.perform(multipart("/api/forms/fpo")
            .file(new MockMultipartFile("certificate", "c.pdf", "application/pdf", PDF))
            .file(new MockMultipartFile("pan", "p.pdf", "application/pdf", PDF))
            .file(new MockMultipartFile("boardResolution", "b.pdf", "application/pdf", PDF))
            .param("fpoName", "Login Test FPO").param("state", "rajasthan").param("district", "Bundi")
            .param("cin", "U01100RJ2022PTC" + rnd()).param("kisanCount", "300").param("name", "Gopal")
            .param("phone", "9876543210").param("email", email).param("consent", "true"))
        .andExpect(status().isCreated())), "$.id");
    String admin = login("chairman", "very-strong-pass");
    String temp = JsonPath.read(json(mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", auth(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"verified\"}"))
        .andExpect(jsonPath("$.account.created").value(true))
        .andExpect(jsonPath("$.account.username").value(email))), "$.account.tempPassword");
    // verifying again does not create a second login
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", auth(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"verified\"}"))
        .andExpect(jsonPath("$.account.created").value(false))
        .andExpect(jsonPath("$.account.tempPassword").doesNotExist());

    String first = JsonPath.read(json(loginCall(email, temp).andExpect(jsonPath("$.role").value("fpo"))
        .andExpect(jsonPath("$.recordId").value(id))), "$.token");
    String fpo = changePassword(first, temp, "kisan-fpo-2026");
    mvc.perform(get("/api/admin/registrations").header("Authorization", auth(fpo)))
        .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
    mvc.perform(get("/api/admin/registrations/" + id + "/files/pan").param("token", fpo)).andExpect(status().isOk());
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", auth(fpo))
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/export/registrations").param("token", fpo)).andExpect(status().isForbidden());
  }

  @Test
  void ngoFormNeedsCoreDocumentsAndDarpanIdIsUnique() throws Exception {
    String darpan = "RJ/2019/0" + rnd();
    var form = multipart("/api/forms/ngo")
        .file(new MockMultipartFile("certificate", "reg.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("pan", "pan.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("authorisation", "auth.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("idProof", "id.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("cert80g", "80g.pdf", "application/pdf", PDF))
        .param("ngoName", "Gram Seva Trust").param("ngoType", "trust").param("regNumber", "T-123")
        .param("darpanId", darpan.toLowerCase()).param("state", "mp").param("district", "Indore")
        .param("yearEstablished", "2012").param("focusArea", "Dairy, women SHG").param("name", "Kailash")
        .param("phone", "9876543210").param("email", "ngo" + rnd() + "@example.com").param("consent", "true");
    String id = JsonPath.read(json(mvc.perform(form).andExpect(status().isCreated())), "$.id");
    mvc.perform(form).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.darpanId").exists());
    mvc.perform(multipart("/api/forms/ngo")
            .file(new MockMultipartFile("certificate", "reg.pdf", "application/pdf", PDF))
            .param("ngoName", "X").param("ngoType", "club").param("consent", "true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.authorisation").exists())
        .andExpect(jsonPath("$.errors.ngoType").exists())
        .andExpect(jsonPath("$.errors.darpanId").exists());

    String admin = login("chairman", "very-strong-pass");
    mvc.perform(get("/api/admin/registrations/" + id).header("Authorization", auth(admin)))
        .andExpect(jsonPath("$.kind").value("ngo"))
        .andExpect(jsonPath("$.name").value("Gram Seva Trust"))
        .andExpect(jsonPath("$.data.darpanId").value(darpan))
        .andExpect(jsonPath("$.files.cert80g").value("80g.pdf"));
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", auth(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"12AB missing\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.account").doesNotExist());
    mvc.perform(get("/api/admin/registrations").param("kind", "ngo").param("status", "rejected").header("Authorization", auth(admin)))
        .andExpect(jsonPath("$.items[*].id", hasItem(id)));
  }

  @Test
  void enquiryInboxIsForAdminsOnly() throws Exception {
    String who = "Inbox Person " + rnd();
    mvc.perform(post("/api/forms/sourcing").contentType(MediaType.APPLICATION_JSON)
            .content("{\"need\":\"Cumin\",\"quantity\":\"5 tonnes\",\"frequency\":\"monthly\",\"name\":\"" + who
                + "\",\"phone\":\"9876543210\",\"consent\":true}"))
        .andExpect(status().isCreated());
    String admin = login("chairman", "very-strong-pass");
    String id = JsonPath.read(json(mvc.perform(get("/api/admin/enquiries").param("q", who).param("status", "new")
            .header("Authorization", auth(admin)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].formKey").value("sourcing"))
        .andExpect(jsonPath("$.items[0].data.need").value("Cumin"))), "$.items[0].id");
    mvc.perform(patch("/api/admin/enquiries/" + id).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"replied\",\"note\":\"Called, sent price list\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("replied"))
        .andExpect(jsonPath("$.handledBy").value("Federation Admin"));
    mvc.perform(patch("/api/admin/enquiries/" + id).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"lost\"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/enquiries").param("from", "19-11-2026").header("Authorization", auth(admin)))
        .andExpect(status().isBadRequest());

    String head = login("rajasthan-head", "state123");
    mvc.perform(get("/api/admin/enquiries").header("Authorization", auth(head))).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/export/enquiries").param("token", head)).andExpect(status().isForbidden());
    byte[] xlsx = mvc.perform(get("/api/admin/export/enquiries").param("token", admin)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(sharedStrings(xlsx)).contains(who).contains("Called, sent price list");
  }

  @Test
  void excelExportFollowsRoleScope() throws Exception {
    String admin = login("chairman", "very-strong-pass");
    byte[] all = mvc.perform(get("/api/admin/export/registrations").param("token", admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("fpc-registrations-")))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(sharedStrings(all)).contains("Aravali Bio Inputs").contains("Saurashtra Kisan FPO").contains("Applied on");

    String head = login("rajasthan-head", "state123");
    byte[] mine = mvc.perform(get("/api/admin/export/registrations").header("Authorization", auth(head)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    assertThat(sharedStrings(mine)).contains("Aravali Bio Inputs").doesNotContain("Saurashtra Kisan FPO");
  }

  @Test
  void dashboardNumbersMapCountsAndActivityLog() throws Exception {
    String admin = login("chairman", "very-strong-pass");
    mvc.perform(get("/api/admin/stats").header("Authorization", auth(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrations.fpo.verified", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.registrations.fpo.pending", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.enquiriesNew").exists());
    String head = login("rajasthan-head", "state123");
    String body = json(mvc.perform(get("/api/admin/stats").header("Authorization", auth(head))).andExpect(status().isOk())
        .andExpect(jsonPath("$.enquiriesNew").doesNotExist()));
    assertThat(body).doesNotContain("\"ngo\""); // the only NGO sample is in MP

    mvc.perform(get("/api/public/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.states.rajasthan.vendor", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.states.mp").doesNotExist()) // MP has only a rejected NGO: nothing verified
        .andExpect(jsonPath("$.totals.fpo", greaterThanOrEqualTo(1)));

    mvc.perform(get("/api/admin/audit").header("Authorization", auth(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].action", hasItem("login")));
    mvc.perform(get("/api/admin/audit").header("Authorization", auth(head))).andExpect(status().isForbidden());
  }

  private String createFpo(String cin, String email) throws Exception {
    return JsonPath.read(json(mvc.perform(fpo(cin, email)).andExpect(status().isCreated())), "$.id");
  }

  private org.springframework.test.web.servlet.RequestBuilder fpo(String cin, String email) {
    return multipart("/api/forms/fpo")
        .file(new MockMultipartFile("certificate", "c.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("pan", "p.pdf", "application/pdf", PDF))
        .file(new MockMultipartFile("boardResolution", "b.pdf", "application/pdf", PDF))
        .param("fpoName", "Edge FPO " + rnd()).param("state", "rajasthan").param("district", "Kota").param("cin", cin)
        .param("kisanCount", "100").param("name", "Hari").param("phone", "9876543210").param("email", email)
        .param("consent", "true");
  }

  private ResultActions decide(String token, String id, String body) throws Exception {
    return mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void signInAtTheSameMomentCannotUndoASwitchOff() throws Exception {
    String admin = login("chairman", "very-strong-pass");
    String username = "race" + rnd();
    String created = json(mvc.perform(post("/api/admin/users").header("Authorization", auth(admin))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"name\":\"Race PH\",\"role\":\"project_head\",\"state\":\"goa\"}")));
    String id = JsonPath.read(created, "$.user.id");
    String temp = JsonPath.read(created, "$.tempPassword");
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 15; round++) {
        mvc.perform(patch("/api/admin/users/" + id).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"active\":true}")).andExpect(status().isOk());
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        var signIn = pool.submit(() -> { go.await(); return loginCall(username, temp).andReturn().getResponse().getStatus(); });
        var switchOff = pool.submit(() -> {
          go.await();
          return mvc.perform(patch("/api/admin/users/" + id).header("Authorization", auth(admin))
              .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andReturn().getResponse().getStatus();
        });
        go.countDown();
        signIn.get();
        assertThat(switchOff.get()).isEqualTo(200);
        mvc.perform(get("/api/admin/users").param("q", username).header("Authorization", auth(admin)))
            .andExpect(jsonPath("$.items[0].active").value(false));
        loginCall(username, temp).andExpect(status().isUnauthorized());
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void verifyingAgainDoesNotSwitchOnALoginTheAdminSwitchedOff() throws Exception {
    String email = "off" + rnd() + "@example.com";
    String id = createFpo("U01100RJ2021PTC" + rnd(), email);
    String admin = login("chairman", "very-strong-pass");
    String temp = JsonPath.read(json(decide(admin, id, "{\"status\":\"verified\"}")), "$.account.tempPassword");
    String userId = JsonPath.read(json(mvc.perform(get("/api/admin/users").param("q", email).header("Authorization", auth(admin)))),
        "$.items[0].id");
    mvc.perform(patch("/api/admin/users/" + userId).header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"active\":false}")).andExpect(status().isOk());

    String head = login("rajasthan-head", "state123");
    decide(head, id, "{\"status\":\"verified\"}").andExpect(status().isOk())
        .andExpect(jsonPath("$.account.message", org.hamcrest.Matchers.containsString("switched off")));
    decide(head, id, "{\"status\":\"pending\"}").andExpect(status().isOk());
    decide(head, id, "{\"status\":\"verified\"}").andExpect(status().isOk())
        .andExpect(jsonPath("$.account.created").value(false));
    loginCall(email, temp).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/users").param("q", email).header("Authorization", auth(admin)))
        .andExpect(jsonPath("$.items[0].active").value(false));
  }

  @Test
  void aRejectedRecordCannotBeRevivedIntoADuplicate() throws Exception {
    String cin = "U01100RJ2020PTC" + rnd();
    String admin = login("chairman", "very-strong-pass");
    String first = createFpo(cin, "a" + rnd() + "@example.com");
    decide(admin, first, "{\"status\":\"rejected\",\"reason\":\"Wrong board resolution\"}").andExpect(status().isOk());
    String second = createFpo(cin, "b" + rnd() + "@example.com"); // allowed: the first one is rejected

    decide(admin, first, "{\"status\":\"verified\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.cin").exists());
    decide(admin, first, "{\"status\":\"pending\"}").andExpect(status().isConflict());
    decide(admin, second, "{\"status\":\"verified\"}").andExpect(status().isOk());
    mvc.perform(get("/api/admin/registrations").param("q", cin).header("Authorization", auth(admin)))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[?(@.status == 'verified')].id").value(org.hamcrest.Matchers.contains(second)));
  }

  @Test
  void theSameCinSentAtTheSameMomentIsSavedOnce() throws Exception {
    String cin = "U01100RJ2019PTC" + rnd();
    int threads = 5;
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<Integer>> calls = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String email = "same" + i + rnd() + "@example.com";
      calls.add(pool.submit(() -> { go.await(); return mvc.perform(fpo(cin, email)).andReturn().getResponse().getStatus(); }));
    }
    go.countDown();
    int created = 0;
    for (var f : calls) {
      int st = f.get();
      assertThat(st).isIn(201, 409);
      if (st == 201) created++;
    }
    pool.shutdown();
    assertThat(created).isEqualTo(1);
  }

  /** All text cells of an .xlsx file (fastexcel writes text into sharedStrings.xml). */
  private static String sharedStrings(byte[] xlsx) throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("export", ".xlsx");
    try {
      java.nio.file.Files.write(tmp, xlsx);
      try (ZipFile zip = new ZipFile(tmp.toFile())) {
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) throw new AssertionError("not an xlsx file");
        return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
      }
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }
}
