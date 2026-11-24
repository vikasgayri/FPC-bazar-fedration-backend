package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.fpcbazaar.api.enquiries.EnquiryRepository;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

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
class RegistrationIntegrationTest {
  static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

  @Autowired MockMvc mvc;
  @Autowired RegistrationRepository registrations;
  @Autowired EnquiryRepository enquiries;
  @Autowired org.fpcbazaar.api.users.UserRepository users;

  private String login(String u, String p) throws Exception {
    String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private static String bearer(String t) { return "Bearer " + t; }

  private static String digits(int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(ThreadLocalRandom.current().nextInt(10));
    return b.toString();
  }

  private static MockMultipartFile pdf(String field) { return new MockMultipartFile(field, field + ".pdf", "application/pdf", PDF); }

  private MockMultipartHttpServletRequestBuilder fpoForm(String state, String cin) {
    return (MockMultipartHttpServletRequestBuilder) multipart("/api/forms/fpo")
        .file(pdf("certificate")).file(pdf("pan")).file(pdf("boardResolution"))
        .param("fpoName", "Test Kisan FPO").param("state", state).param("district", "Kota").param("cin", cin)
        .param("kisanCount", "500").param("odop", "Garlic").param("name", "Ramesh").param("phone", "+91 98765 43210")
        .param("email", "fpo" + digits(6) + "@example.com")
        .param("lang", "hi").param("consent", "true").param("page", "/hi/fpo-registration");
  }

  private MockMultipartHttpServletRequestBuilder vendorForm(String company, String gst) {
    return (MockMultipartHttpServletRequestBuilder) multipart("/api/forms/vendor")
        .file(new MockMultipartFile("logo", "logo.png", "image/png", PNG)).file(pdf("certificate"))
        .param("companyName", company).param("gst", gst).param("turnover", "50 lakh").param("productCategory", "spices")
        .param("supplyState", "rajasthan").param("state", "rajasthan").param("district", "Jaipur")
        .param("name", "Asha").param("phone", "9876543210").param("email", "Asha" + digits(6) + "@Example.com")
        .param("about", "Spice processor").param("productList", "Cumin\nCoriander").param("pastWork", "Supplied 10 FPOs")
        .param("consent", "true");
  }

  private String create(MockMultipartHttpServletRequestBuilder req) throws Exception {
    String body = mvc.perform(req).andExpect(status().isCreated())
        .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.status").value("pending"))
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void fpoRegistrationIsPendingAndDocumentsOpenOnlyWithToken() throws Exception {
    String id = create(fpoForm("rajasthan", "u01100rj2024 ptc" + digits(6)));
    String admin = login("chairman", "very-strong-pass");

    mvc.perform(get("/api/admin/registrations/" + id).header("Authorization", bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("fpo"))
        .andExpect(jsonPath("$.status").value("pending"))
        .andExpect(jsonPath("$.name").value("Test Kisan FPO"))
        .andExpect(jsonPath("$.state").value("rajasthan"))
        .andExpect(jsonPath("$.data.contact").value("Ramesh"))
        .andExpect(jsonPath("$.data.phone").value("9876543210"))
        .andExpect(jsonPath("$.data.cin", containsString("U01100RJ2024PTC")))
        .andExpect(jsonPath("$.data.fpoName").doesNotExist())
        .andExpect(jsonPath("$.data.consent").doesNotExist())
        .andExpect(jsonPath("$.files.certificate").value("certificate.pdf"));

    String url = "/api/admin/registrations/" + id + "/files/certificate";
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
    mvc.perform(get(url).param("token", admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().bytes(PDF));
    // ?token= is accepted only on document links
    mvc.perform(get("/api/admin/registrations").param("token", admin)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/registrations/" + id + "/files/nope").param("token", admin)).andExpect(status().isNotFound());
  }

  @Test
  void missingOrFakeDocumentsAreRefusedAndNothingIsSaved() throws Exception {
    long before = registrations.count();
    mvc.perform(multipart("/api/forms/fpo")
            .file(new MockMultipartFile("pan", "pan.pdf", "application/pdf", "MZ this is not a pdf".getBytes()))
            .file(pdf("boardResolution")).file(pdf("aadhaar"))
            .param("fpoName", "X").param("state", "atlantis").param("district", "Kota").param("cin", "U1")
            .param("kisanCount", "0").param("name", "R").param("phone", "12345").param("consent", "true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.errors.certificate").exists())
        .andExpect(jsonPath("$.errors.pan").value("Only PDF, JPG or PNG files are allowed."))
        .andExpect(jsonPath("$.errors.aadhaar").exists())
        .andExpect(jsonPath("$.errors.state").exists())
        .andExpect(jsonPath("$.errors.kisanCount").exists())
        .andExpect(jsonPath("$.errors.phone").exists());
    assertThat(registrations.count()).isEqualTo(before);
  }

  @Test
  void enquiryFormsConsentHoneypotAndUnknownForms() throws Exception {
    long before = enquiries.count();
    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Meena\",\"phone\":\"09876543210\",\"message\":\"Hello\",\"lang\":\"en\",\"consent\":true,\"extra\":\"dropped\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.ok").value(true));
    assertThat(enquiries.count()).isEqualTo(before + 1);

    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Meena\",\"phone\":\"9876543210\",\"message\":\"Hello\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.consent").exists());

    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bot\",\"phone\":\"9876543210\",\"message\":\"spam\",\"consent\":true,\"website\":\"http://spam\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    assertThat(enquiries.count()).isEqualTo(before + 1);

    mvc.perform(post("/api/forms/nope").contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true}"))
        .andExpect(status().isNotFound());
    // a registration form needs its documents, so JSON without files is refused
    mvc.perform(post("/api/forms/member").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"A\",\"phone\":\"9876543210\",\"state\":\"goa\",\"consent\":true}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.feeSlip").exists());
  }

  @Test
  void vendorRejectVerifyAndPublicPage() throws Exception {
    String gst = "08AAAAA" + digits(4) + "A1Z5";
    String name = "Mewar Spice Works " + digits(4);
    String id = create(vendorForm(name, gst));
    String slug = registrations.findById(id).orElseThrow().getSlug();
    assertThat(slug).startsWith("mewar-spice-works-");
    String admin = login("chairman", "very-strong-pass");

    // pending: not public
    mvc.perform(get("/api/public/vendors")).andExpect(jsonPath("$.items[*].slug", not(hasItem(slug))));
    mvc.perform(get("/api/public/vendors/" + slug)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/vendors/" + slug + "/logo")).andExpect(status().isNotFound());

    // same GST again while pending: refused
    mvc.perform(vendorForm("Other Name", gst.toLowerCase())).andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.gst").exists());
    // same company name: gets its own slug
    String id2 = create(vendorForm(name, "08BBBBB" + digits(4) + "A1Z5"));
    assertThat(registrations.findById(id2).orElseThrow().getSlug()).isEqualTo(slug + "-2");

    // reject needs a reason
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"GST not valid\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("rejected"))
        .andExpect(jsonPath("$.reason").value("GST not valid"))
        .andExpect(jsonPath("$.decidedBy").value("Federation Admin"))
        .andExpect(jsonPath("$.decidedAt").exists());
    mvc.perform(get("/api/admin/registrations").param("kind", "vendor").param("status", "rejected")
            .header("Authorization", bearer(admin)))
        .andExpect(jsonPath("$.items[*].id", hasItem(id)))
        .andExpect(jsonPath("$.items[*].status", everyItem(is("rejected"))));
    mvc.perform(get("/api/public/vendors")).andExpect(jsonPath("$.items[*].slug", not(hasItem(slug))));

    // verify: now public, without private fields
    mvc.perform(patch("/api/admin/registrations/" + id).header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"verified\",\"reason\":\"ignored\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value(""))
        .andExpect(jsonPath("$.history.length()").value(2))
        .andExpect(jsonPath("$.account.created").value(true))
        .andExpect(jsonPath("$.account.username", containsString("@example.com")))
        .andExpect(jsonPath("$.account.tempPassword").exists());
    mvc.perform(get("/api/public/vendors/" + slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.logoUrl").value("https://api.example.org/api/public/vendors/" + slug + "/logo"))
        .andExpect(jsonPath("$.data.productList").value("Cumin\nCoriander"))
        .andExpect(jsonPath("$.data.contact").value("Asha"))
        .andExpect(jsonPath("$.data.gst").doesNotExist())
        .andExpect(jsonPath("$.data.turnover").doesNotExist())
        .andExpect(jsonPath("$.data.email").doesNotExist())
        .andExpect(jsonPath("$.files.certificate").value("certificate.pdf"))
        .andExpect(jsonPath("$.id").doesNotExist());
    mvc.perform(get("/api/public/vendors/" + slug + "/logo"))
        .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
    mvc.perform(get("/api/public/vendors")).andExpect(jsonPath("$.items[*].slug", hasItem(slug)));
  }

  @Test
  void roleScopeIsEnforcedByTheServer() throws Exception {
    String gujarat = create(fpoForm("gujarat", "U01100GJ2024PTC" + digits(6)));
    String rajasthan = create(fpoForm("rajasthan", "U01100RJ2023PTC" + digits(6)));
    String head = login("rajasthan-head", "state123");

    mvc.perform(get("/api/admin/registrations").header("Authorization", bearer(head)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].state", everyItem(is("rajasthan"))))
        .andExpect(jsonPath("$.items[*].id", hasItem(rajasthan)));
    mvc.perform(get("/api/admin/registrations/" + gujarat).header("Authorization", bearer(head)))
        .andExpect(status().isNotFound());
    mvc.perform(patch("/api/admin/registrations/" + gujarat).header("Authorization", bearer(head))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"verified\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/registrations/" + gujarat + "/files/pan").param("token", head))
        .andExpect(status().isNotFound());
    mvc.perform(patch("/api/admin/registrations/" + rajasthan).header("Authorization", bearer(head))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"verified\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.decidedBy").value("Rajasthan State Head"));

    String projectHead = login("project-head", "project123");
    mvc.perform(patch("/api/admin/registrations/" + rajasthan).header("Authorization", bearer(projectHead))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"x\"}"))
        .andExpect(status().isForbidden());

    // demo vendor login sees only its own record
    String vendor = login("vendor", "vendor123");
    String body = mvc.perform(get("/api/admin/registrations").header("Authorization", bearer(vendor)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    List<String> names = JsonPath.read(body, "$.items[*].name");
    assertThat(names).containsExactly("Aravali Bio Inputs");

    // public FPO list: verified only
    mvc.perform(get("/api/public/fpos"))
        .andExpect(jsonPath("$.items[*].name", hasItem("Hadoti Kisan Producer Company")))
        .andExpect(jsonPath("$.items[*].name", not(hasItem("Saurashtra Kisan FPO"))))
        .andExpect(jsonPath("$.items[*].cin").doesNotExist());
  }

  @Test
  void withoutEmailServiceAChosenPasswordIsIgnoredAndTheLoginComesAfterVerification() throws Exception {
    String gst = "08CCCCC" + digits(4) + "A1Z5";
    MockMultipartHttpServletRequestBuilder form = vendorForm("No Mail Vendor " + digits(4), gst);
    form.param("password", "VendorPass2026");
    String body = mvc.perform(form).andExpect(status().isCreated()).andExpect(jsonPath("$.login").value("after_verification"))
        .andReturn().getResponse().getContentAsString();
    String id = JsonPath.read(body, "$.id");
    assertThat(registrations.findById(id).orElseThrow().getData()).doesNotContainKey("password");
    assertThat(users.findFirstByRecordId(id)).isEmpty();
  }
}
