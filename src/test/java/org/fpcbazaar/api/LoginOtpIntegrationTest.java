package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.fpcbazaar.api.notifications.BrevoTransport;
import org.fpcbazaar.api.notifications.MailCipher;
import org.fpcbazaar.api.notifications.MailJob;
import org.fpcbazaar.api.security.JwtService;
import org.fpcbazaar.api.security.Role;
import org.fpcbazaar.api.users.LoginOtp;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every sign-in: password, then the 6-digit code from the email. */
@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.admin.username=admin@example.org", "app.admin.password=StrongAdmin123",
    "app.storage.dir=target/test-uploads-otp", "app.rate-limit.login-failures-per-window=1000",
    "app.login-otp.enabled=true", "app.login-otp.max-issues-per-window=1000",
    "app.mail.enabled=true", "app.mail.api-key=test-only", "app.mail.from=sender@example.org",
    "app.mail.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.mail.frontend-url=https://frontend.example.org", "app.mail.initial-delay=86400000"
})
@AutoConfigureMockMvc
class LoginOtpIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired MailCipher cipher;
  @Autowired JwtService jwt;
  @MockitoBean BrevoTransport transport;
  /** What the (mocked) email provider received; sign-in codes are sent at once by a background thread. */
  final java.util.Queue<org.fpcbazaar.api.notifications.MailPayload> sent = new java.util.concurrent.ConcurrentLinkedQueue<>();

  @BeforeEach
  void clean() {
    mongo.remove(new Query(), MailJob.class);
    mongo.remove(new Query(), LoginOtp.class);
    reset(transport);
    sent.clear();
    when(transport.send(any())).thenAnswer((inv) -> { sent.add(inv.getArgument(0)); return "<test@brevo>"; });
  }

  private String newUser(Role role) {
    String email = "otp" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    users.save(new User(email, encoder.encode("GoodPassword123"), role, "Otp User", role == Role.STATE_HEAD ? "rajasthan" : null, null));
    return email;
  }

  private ResultActions login(String u, String p) throws Exception {
    return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"));
  }

  private ResultActions verify(String challenge, String code) throws Exception {
    return mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
        .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}"));
  }

  private ResultActions resend(String challenge) throws Exception {
    return mvc.perform(post("/api/auth/resend-otp").contentType(MediaType.APPLICATION_JSON)
        .content("{\"challenge\":\"" + challenge + "\"}"));
  }

  private static String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

  /** The code from the n-th sign-in email to this address (waits for the immediate send). */
  private String codeFor(String email, int n) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var mine = sent.stream().filter((m) -> m.to().equals(email) && m.subject().contains("sign-in code")).toList();
      if (mine.size() >= n) {
        String text = mine.get(n - 1).text();
        return text.substring(text.indexOf("sign-in code is:") + 16).trim().substring(0, 6);
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no sign-in email " + n + " to " + email);
  }

  private String codeFor(String email) throws InterruptedException { return codeFor(email, 1); }

  private static String other(String code) { return code.equals("000000") ? "111111" : "000000"; }

  @Test
  void passwordAloneGivesNoTokenAndTheEmailedCodeSignsInOnce() throws Exception {
    String email = newUser(Role.STATE_HEAD);
    String start = body(login(email, "GoodPassword123").andExpect(status().isOk())
        .andExpect(jsonPath("$.otpRequired").value(true)).andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.matchesPattern("^o\\*{6}.@example\\.org$")))
        .andExpect(jsonPath("$.resendInSeconds").value(60)));
    String challenge = JsonPath.read(start, "$.challenge");
    String code = codeFor(email);
    assertThat(code).matches("\\d{6}");
    // only a hash is stored
    LoginOtp stored = mongo.findById(challenge, LoginOtp.class);
    assertThat(stored.codeHash).hasSize(64).doesNotContain(code);

    verify(challenge, other(code)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_wrong"))
        .andExpect(jsonPath("$.message").value("Wrong code. 4 tries left."));
    String token = JsonPath.read(body(verify(challenge, code).andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("state_head"))), "$.token");
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    verify(challenge, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
    assertThat(users.findByUsername(email).orElseThrow().getLastLoginAt()).isNotNull();
  }

  @Test
  void wrongPasswordSendsNoCode() throws Exception {
    String email = newUser(Role.PROJECT_HEAD);
    login(email, "WrongPassword123").andExpect(status().isUnauthorized());
    assertThat(mongo.count(Query.query(Criteria.where("kind").is("login_code")), MailJob.class)).isZero();
  }

  @Test
  void fiveWrongCodesEndTheSignIn() throws Exception {
    String email = newUser(Role.PROJECT_HEAD);
    String challenge = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    String code = codeFor(email);
    for (int i = 0; i < 4; i++) verify(challenge, other(code)).andExpect(jsonPath("$.errors.code").value("otp_wrong"));
    verify(challenge, other(code)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
    verify(challenge, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
  }

  @Test
  void expiredCodeIsRefused() throws Exception {
    String email = newUser(Role.PROJECT_HEAD);
    String challenge = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    String code = codeFor(email);
    mongo.updateFirst(Query.query(Criteria.where("_id").is(challenge)), new Update().set("expiresAt", Instant.now().minusSeconds(1)), LoginOtp.class);
    verify(challenge, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
  }

  @Test
  void resendWaitsThenReplacesTheCode() throws Exception {
    String email = newUser(Role.PROJECT_HEAD);
    String challenge = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    String first = codeFor(email);
    resend(challenge).andExpect(status().isTooManyRequests());
    mongo.updateFirst(Query.query(Criteria.where("_id").is(challenge)), new Update().set("lastSentAt", Instant.now().minusSeconds(120)), LoginOtp.class);
    resend(challenge).andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value(challenge));
    String second = codeFor(email, 2);
    if (!second.equals(first)) verify(challenge, first).andExpect(jsonPath("$.errors.code").value("otp_wrong"));
    verify(challenge, second).andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());
  }

  @Test
  void aNewSignInCancelsTheOldOneAndAPasswordChangeCancelsBoth() throws Exception {
    String email = newUser(Role.PROJECT_HEAD);
    String one = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    String codeOne = codeFor(email);
    String two = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    verify(one, codeOne).andExpect(jsonPath("$.errors.code").value("otp_expired"));
    String codeTwo = codeFor(email, 2);
    // password changed / login switched off between the two steps: tokenVersion moves on
    mongo.updateFirst(Query.query(Criteria.where("username").is(email)), new Update().inc("tokenVersion", 1), User.class);
    verify(two, codeTwo).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
  }

  @Test
  void loginWithoutEmailCannotGetACodeAndNewLoginsMustBeEmails() throws Exception {
    users.save(new User("old-style-login", encoder.encode("GoodPassword123"), Role.PROJECT_HEAD, "Old", "rajasthan", null));
    login("old-style-login", "GoodPassword123").andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.username").value("no_email"));

    String admin = "Bearer " + jwt.issue(users.findByUsername("admin@example.org").orElseThrow());
    mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"rj-head\",\"name\":\"RJ\",\"role\":\"state_head\",\"state\":\"rajasthan\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.username").exists());
  }

  @Test
  void codeMustBeSixDigitsAndChallengeMustBeReal() throws Exception {
    verify("not-a-real-challenge-value-123", "123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").value("otp_expired"));
    String email = newUser(Role.PROJECT_HEAD);
    String challenge = JsonPath.read(body(login(email, "GoodPassword123")), "$.challenge");
    verify(challenge, "12ab").andExpect(status().isBadRequest());
    // a malformed code does not use up a try
    assertThat(mongo.findById(challenge, LoginOtp.class).attempts).isZero();
  }
}
