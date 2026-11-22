package org.fpcbazaar.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "de.flapdoodle.mongodb.embedded.version=7.0.14",
    "app.jwt.secret=test-secret-test-secret-test-secret-123",
    "app.storage.dir=target/test-uploads-limit",
    "app.rate-limit.forms-per-minute=2"
})
@AutoConfigureMockMvc
class FormRateLimitTest {
  @Autowired MockMvc mvc;

  @Test
  void tooManySubmissionsFromOneAddressGet429() throws Exception {
    String body = "{\"name\":\"A\",\"phone\":\"9876543210\",\"message\":\"Hi\",\"consent\":true}";
    for (int i = 0; i < 2; i++)
      mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    mvc.perform(post("/api/forms/contact").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.ok").value(false));
  }
}
