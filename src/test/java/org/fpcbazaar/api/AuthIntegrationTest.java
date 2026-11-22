package org.fpcbazaar.api;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=Chairman",
    "app.admin.password=very-strong-pass",
    "app.storage.dir=target/test-uploads",
    "app.seed-demo=true",
    "app.rate-limit.login-failures-per-window=3"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;

  private String login(String u, String p) throws Exception {
    String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  @Test
  void bootstrapAdminCanLoginCaseInsensitive() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\" chairman \",\"password\":\"very-strong-pass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", not(emptyOrNullString())))
        .andExpect(jsonPath("$.role").value("admin"))
        .andExpect(jsonPath("$.name").value("Federation Admin"));
  }

  @Test
  void meReturnsRoleAndState() throws Exception {
    String t = login("rajasthan-head", "state123");
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("state_head"))
        .andExpect(jsonPath("$.state").value("rajasthan"));
  }

  @Test
  void wrongPasswordGivesJsonErrorAndLocksAfterLimit() throws Exception {
    for (int i = 0; i < 3; i++)
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"project-head\",\"password\":\"nope\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.ok").value(false))
          .andExpect(jsonPath("$.message").value("Wrong username or password."));
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"project-head\",\"password\":\"project123\"}"))
        .andExpect(status().isTooManyRequests());
    // other users on the same IP are not locked out
    login("fpo", "fpo123");
  }

  @Test
  void missingFieldsGive400() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.errors.password").exists());
  }

  @Test
  void adminApisNeedToken() throws Exception {
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.ok").value(false));
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/registrations")).andExpect(status().isUnauthorized());
  }

  @Test
  void unknownPathsAreClosedAndHealthIsOpen() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/something-else")).andExpect(status().isUnauthorized());
  }

  @Test
  void corsAllowsFrontendOrigin() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/login")
            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  @Test
  void manyWrongPasswordsSentAtOnceCannotPassTheLimit() throws Exception {
    int threads = 8;
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<Integer>> calls = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++)
      calls.add(pool.submit(() -> {
        go.await();
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"vendor\",\"password\":\"wrong-guess\"}")).andReturn().getResponse().getStatus();
      }));
    go.countDown();
    int checked = 0;
    int limited = 0;
    for (var f : calls) {
      int st = f.get();
      if (st == 401) checked++;
      else if (st == 429) limited++;
    }
    pool.shutdown();
    org.assertj.core.api.Assertions.assertThat(checked).isEqualTo(3); // limit in this test = 3
    org.assertj.core.api.Assertions.assertThat(limited).isEqualTo(threads - 3);
  }

  @Test
  void tooLongPasswordsGiveAClearErrorNot500() throws Exception {
    String longAscii = "a1".repeat(50); // 100 bytes
    String hindi = "किसान".repeat(5) + "12345"; // 25 Hindi chars = 75 bytes + 5
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"" + longAscii + "\"}"))
        .andExpect(status().isUnauthorized());
    String t = login("admin", "admin123");
    for (String next : new String[] {longAscii, hindi})
      mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
              .content("{\"currentPassword\":\"admin123\",\"newPassword\":\"" + next + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.newPassword").exists());
  }
}
