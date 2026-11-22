package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fpcbazaar.api.forms.FormValidator;
import org.fpcbazaar.api.notifications.*;
import org.fpcbazaar.api.registrations.*;
import org.fpcbazaar.api.security.*;
import org.fpcbazaar.api.users.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
class Step4IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired JwtService jwt;
  @Autowired MailCipher cipher;
  @Autowired MailQueue queue;
  @Autowired RegistrationService registrations;
  @Autowired UserWrites writes;
  @MockitoBean BrevoTransport transport;

  @BeforeEach void cleanQueue() { mongo.remove(new Query(), MailJob.class); reset(transport); when(transport.send(any())).thenReturn("<test@brevo>"); }
  private String unique() { return UUID.randomUUID().toString(); }
  private User user() { return users.save(new User(unique()+"@example.org", encoder.encode("OriginalPass123"), Role.ADMIN, "Tester", null, null)); }
  private String auth() { return "Bearer " + jwt.issue(users.findByUsername("admin@example.org").orElseThrow()); }
  private List<MailJob> jobs() { return mongo.findAll(MailJob.class); }
  private String forgot(User u) throws Exception {
    mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\""+u.getUsername()+"\"}")).andExpect(status().isOk());
    String text = cipher.decrypt(jobs().getLast().payload).text();
    return text.substring(text.indexOf("#token=")+7).split("\\s")[0];
  }
  private org.springframework.test.web.servlet.ResultActions resetCall(String token) throws Exception {
    return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\""+token+"\",\"newPassword\":\"NewSecurePass123\"}"));
  }

  @Test void registrationReceiptVerificationAndLoginAreQueuedEncrypted() {
    Registration r=registrations.create("vendor",new FormValidator.Result(Map.of("companyName","Test Vendor","name","Tester",
        "email",unique()+"@example.org","state","rajasthan","gst",unique()),Map.of()),"hi",null);
    User admin=users.findByUsername("admin@example.org").orElseThrow();
    var result=registrations.decide(new CurrentUser(admin.getId(),admin.getUsername(),admin.getName(),Role.ADMIN,null,null),r.getId(),"verified",null);
    assertThat(jobs()).hasSize(3);
    assertThat(jobs()).allMatch(j -> !j.payload.contains("@example.org") && !j.payload.contains(result.account().tempPassword()));
    assertThat(jobs().stream().map(j -> cipher.decrypt(j.payload).text())).anyMatch(t -> t.contains("कंपनी / वेंडर पंजीकरण आवेदन मिल गया है"))
        .anyMatch(t -> t.contains(result.account().tempPassword())).anyMatch(t -> t.contains("स्वीकृत"));
    registrations.decide(new CurrentUser(admin.getId(),admin.getUsername(),admin.getName(),Role.ADMIN,null,null),r.getId(),"verified",null);
    assertThat(jobs()).hasSize(3); // no duplicate mail for an unchanged decision
    queue.drain();
    verify(transport,times(3)).send(any());
    assertThat(jobs()).allMatch(j -> j.status.equals("accepted") && j.payload == null);
  }

  @Test void rejectionIncludesReasonWithoutCreatingAccount() {
    Registration r=registrations.create("member",new FormValidator.Result(Map.of("name","Tester","email",unique()+"@example.org"),Map.of()),"en",null);
    User a=users.findByUsername("admin@example.org").orElseThrow();
    registrations.decide(new CurrentUser(a.getId(),a.getUsername(),a.getName(),Role.ADMIN,null,null),r.getId(),"rejected","Missing certificate");
    assertThat(jobs()).hasSize(2);
    assertThat(jobs().stream().map(j -> cipher.decrypt(j.payload).text())).anyMatch(t -> t.contains("Missing certificate"));
    assertThat(users.findFirstByRecordId(r.getId())).isEmpty();
  }

  @Test void enquiryReceiptAndHoneypot() throws Exception {
    String form="{\"name\":\"Tester\",\"phone\":\"9876543210\",\"email\":\"tester@example.org\",\"subject\":\"Question\",\"message\":\"Hello\",\"consent\":true}";
    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isCreated());
    assertThat(jobs()).hasSize(1);
    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON).content(form.replace("\"consent\":true","\"website\":\"spam\",\"consent\":true"))).andExpect(status().isOk());
    assertThat(jobs()).hasSize(1);
  }

  @Test void adminCreateAndResetEmailsContainIssuedPasswordButStatusDoesNot() throws Exception {
    String created=mvc.perform(post("/api/admin/users").header("Authorization",auth()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\""+unique()+"@example.org\",\"name\":\"Head\",\"role\":\"state_head\",\"state\":\"rajasthan\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String id=JsonPath.read(created,"$.user.id"), temp=JsonPath.read(created,"$.tempPassword");
    assertThat(cipher.decrypt(jobs().getFirst().payload).text()).contains(temp);
    mvc.perform(post("/api/admin/users/"+id+"/reset-password").header("Authorization",auth())).andExpect(status().isOk());
    assertThat(jobs()).hasSize(2);
    String status=mvc.perform(get("/api/admin/notifications").header("Authorization",auth())).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(status).doesNotContain(temp,"payload","@example.org");
    mvc.perform(get("/api/admin/notifications")).andExpect(status().isUnauthorized());
    User head=users.findById(id).orElseThrow();
    mvc.perform(get("/api/admin/notifications").header("Authorization","Bearer "+jwt.issue(head))).andExpect(status().isForbidden());
  }

  @Test void selfServiceResetIsSingleUseAndRevokesOldSessions() throws Exception {
    User u=user(); String previous=jwt.issue(u); String token=forgot(u);
    org.bson.Document raw=mongo.getCollection("users").find(new org.bson.Document("username",u.getUsername())).first();
    assertThat(raw.toJson()).doesNotContain(token);
    resetCall(token).andExpect(status().isOk());
    resetCall(token).andExpect(status().isBadRequest());
    mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+previous)).andExpect(status().isUnauthorized());
    assertThat(encoder.matches("NewSecurePass123", users.findById(u.getId()).orElseThrow().getPasswordHash())).isTrue();
  }

  @Test void expiredResetAndResetAfterAdministrativeChangeAreRejected() throws Exception {
    User u=user(); String token=forgot(u);
    mongo.updateFirst(Query.query(Criteria.where("_id").is(u.getId())),new Update().set("resetExpiresAt",Instant.now().minusSeconds(1)),User.class);
    resetCall(token).andExpect(status().isBadRequest());
    String next=forgot(u);
    writes.apply(u.getId(),new Update().inc("tokenVersion",1));
    resetCall(next).andExpect(status().isBadRequest());
    assertThat(encoder.matches("OriginalPass123", users.findById(u.getId()).orElseThrow().getPasswordHash())).isTrue();
  }

  @Test void unknownAndDisabledAccountsUseSameResponseAndSendNothing() throws Exception {
    User u=user(); writes.apply(u.getId(), new Update().set("active",false));
    String response=null;
    for(String username:List.of(u.getUsername(),unique()+"@example.org")) {
      String current=mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\""+username+"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      if(response!=null) assertThat(current).isEqualTo(response); response=current;
    }
    assertThat(jobs()).isEmpty();
  }

  @Test void transientFailureRetriesAndPermanentFailureRequiresAdminRetry() throws Exception {
    queue.enqueue("test",new MailPayload("test@example.org","Subject","Body"),null);
    doThrow(new BrevoTransport.DeliveryFailure("brevo_http_429",true)).doReturn("<test@brevo>").when(transport).send(any());
    queue.drain();
    MailJob j=jobs().getFirst(); assertThat(j.status).isEqualTo("pending"); assertThat(j.attempts).isEqualTo(1);
    mongo.updateFirst(Query.query(Criteria.where("_id").is(j.id)),new Update().set("nextAttempt",Instant.now().minusSeconds(1)),MailJob.class);
    queue.drain(); assertThat(jobs().getFirst().status).isEqualTo("accepted");
    queue.enqueue("test",new MailPayload("test@example.org","Subject","Body"),null);
    doThrow(new BrevoTransport.DeliveryFailure("brevo_http_401",false)).when(transport).send(any());
    queue.drain();
    MailJob failed=jobs().stream().filter(x -> x.status.equals("failed")).findFirst().orElseThrow();
    mvc.perform(post("/api/admin/notifications/"+failed.id+"/retry").header("Authorization",auth())).andExpect(status().isOk());
    doReturn("<test@brevo>").when(transport).send(any()); queue.drain();
    assertThat(jobs()).allMatch(x -> x.status.equals("accepted") && x.payload==null);
  }

  @Test void expiredCredentialIsNotSentAndCipherRejectsTampering() {
    queue.enqueue("expired",new MailPayload("test@example.org","Subject","secret"),Instant.now().minusSeconds(1));
    queue.drain(); verifyNoInteractions(transport);
    assertThat(jobs().getFirst().status).isEqualTo("expired"); assertThat(jobs().getFirst().payload).isNull();
    String encrypted=cipher.encrypt(new MailPayload("test@example.org","Subject","secret"));
    assertThatThrownBy(() -> cipher.decrypt("AAAAAAAAAAAAAAAA."+encrypted.split("\\.")[1])).isInstanceOf(IllegalStateException.class);
  }

  @Test void sameResetTokenCannotBeConsumedConcurrently() throws Exception {
    User u=user(); String token=forgot(u);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one=executor.submit(() -> resetCall(token).andReturn().getResponse().getStatus());
      var two=executor.submit(() -> resetCall(token).andReturn().getResponse().getStatus());
      assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,400);
    }
  }

  @Test void resetPageAndAssetsArePublicButDoNotExposeSecrets() throws Exception {
    mvc.perform(get("/reset-password")).andExpect(status().isOk())
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/reset-password.js")).andExpect(status().isOk());
    mvc.perform(get("/reset-password.css")).andExpect(status().isOk());
  }

  @Test void onlyOneLoginCanBeBoundToARegistrationEvenWithDifferentEmails() {
    String recordId = unique();
    users.save(new User(unique()+"@example.org",encoder.encode("Password123"),Role.VENDOR,"One",null,recordId));
    assertThatThrownBy(() -> users.save(new User(unique()+"@example.org",encoder.encode("Password123"),Role.VENDOR,"Two",null,recordId)))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test void malformedNotificationLimitIs400Not500() throws Exception {
    mvc.perform(get("/api/admin/notifications?limit=oops").header("Authorization",auth())).andExpect(status().isBadRequest());
  }
}
