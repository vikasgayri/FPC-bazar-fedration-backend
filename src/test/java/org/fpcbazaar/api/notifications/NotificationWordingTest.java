package org.fpcbazaar.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationDecidedEvent;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.registrations.Status;
import org.fpcbazaar.api.security.Role;
import org.fpcbazaar.api.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for the text of every notification email (no Spring, no MongoDB). */
class NotificationWordingTest {
  private MailQueue queue;
  private RegistrationRepository registrations;
  private Notifications notifications;
  private final ArgumentCaptor<MailPayload> mail = ArgumentCaptor.forClass(MailPayload.class);

  @BeforeEach
  void setUp() {
    queue = mock(MailQueue.class);
    when(queue.enqueue(anyString(), any(), any())).thenReturn(true);
    registrations = mock(RegistrationRepository.class);
    MailProperties props = new MailProperties(true, "key", "noreply@fpc.example", "FPC Bazaar Federation", null,
        "https://www.fpc.example/", false, 8, Duration.ofMinutes(15));
    AppProperties app = new AppProperties(null, null, null, null, "https://api.fpc.example/", false, null);
    notifications = new Notifications(queue, registrations, props, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC), app);
  }

  private MailPayload sent() {
    verify(queue).enqueue(anyString(), mail.capture(), any());
    return mail.getValue();
  }

  @Test
  void enquiryIsAcknowledgedAsARequestNotAnApplication() {
    notifications.received("ravi@example.org", "Ravi", "contact", "e1", "en");
    MailPayload m = sent();
    assertThat(m.subject()).isEqualTo("FPC Bazaar — We received your request");
    assertThat(m.text()).contains("We have received your message.").contains("contact you soon")
        .doesNotContain("application").doesNotContain("contact application");
  }

  @Test
  void quoteEnquiryInHindiHasHindiSubjectAndLabel() {
    notifications.received("ravi@example.org", "Ravi", "quote", "e2", "hi");
    MailPayload m = sent();
    assertThat(m.subject()).isEqualTo("FPC Bazaar — आपका संदेश मिल गया");
    assertThat(m.text()).contains("कोटेशन अनुरोध मिल गया है").contains("संदर्भ संख्या: e2");
  }

  @Test
  void registrationsUseReadableNames() {
    notifications.received("a@example.org", "Asha", "projecthead", "r1", "en");
    MailPayload m = sent();
    assertThat(m.subject()).isEqualTo("FPC Bazaar — Application received");
    assertThat(m.text()).contains("your Project Head application").doesNotContain("projecthead");
  }

  @Test
  void hindiRejectionHasHindiSubjectAndReason() {
    Registration r = new Registration();
    r.setId("r9");
    r.setKind("fpo");
    r.setName("Hadoti FPO");
    r.setLang("hi");
    Map<String, String> data = new LinkedHashMap<>();
    data.put("email", "fpo@example.org");
    r.setData(data);
    when(registrations.findById("r9")).thenReturn(Optional.of(r));
    notifications.decided(new RegistrationDecidedEvent("r9", "fpo", Status.PENDING, Status.REJECTED, "CIN गलत है", "Admin"));
    MailPayload m = sent();
    assertThat(m.subject()).isEqualTo("FPC Bazaar — आवेदन अस्वीकृत");
    assertThat(m.text()).contains("FPO पंजीकरण आवेदन").contains("कारण: CIN गलत है");
  }

  @Test
  void englishVerificationNamesTheApplication() {
    Registration r = new Registration();
    r.setId("r10");
    r.setKind("vendor");
    r.setName("Mewar Spices");
    r.setLang("en");
    r.setData(new LinkedHashMap<>(Map.of("email", "v@example.org")));
    when(registrations.findById("r10")).thenReturn(Optional.of(r));
    notifications.decided(new RegistrationDecidedEvent("r10", "vendor", Status.PENDING, Status.VERIFIED, "", "Admin"));
    MailPayload m = sent();
    assertThat(m.subject()).isEqualTo("FPC Bazaar — Application verified");
    assertThat(m.text()).contains("Your company / vendor registration has been verified.");
  }

  @Test
  void loginEmailLinksToTheAdminSignInPage() {
    User u = new User("v@example.org", "hash", Role.VENDOR, "Mewar Spices", "rajasthan", "r10");
    notifications.account(u, "Temp12345abc", false);
    MailPayload m = sent();
    assertThat(m.text()).contains("Sign in: https://www.fpc.example/admin").contains("Temp12345abc");
  }
}
