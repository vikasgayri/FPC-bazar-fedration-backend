package org.fpcbazaar.api.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BrevoTransport {
  private final MailProperties props;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  public BrevoTransport(MailProperties props) {
    this.props = props;
    if (props.enabled() && (props.apiKey() == null || props.apiKey().isBlank()
        || !Notifications.isEmail(props.from()) || props.from().endsWith("@example.com")))
      throw new IllegalStateException("Email requires BREVO_API_KEY and a real verified MAIL_FROM address");
    if (props.maxAttempts() < 1 || props.maxAttempts() > 20)
      throw new IllegalStateException("MAIL_MAX_ATTEMPTS must be between 1 and 20");
    URI frontend = URI.create(props.frontendUrl());
    if (frontend.getHost() == null || !("https".equals(frontend.getScheme()) || "http".equals(frontend.getScheme()))
        || frontend.getRawQuery() != null || frontend.getRawFragment() != null || frontend.getUserInfo() != null)
      throw new IllegalStateException("FRONTEND_URL must be an http(s) base URL without credentials, query or fragment");
  }

  public String send(MailPayload mail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sender", Map.of("name", props.fromName(), "email", props.from()));
    payload.put("to", List.of(Map.of("email", mail.to())));
    payload.put("subject", mail.subject());
    payload.put("textContent", mail.text());
    if (props.sandbox()) payload.put("headers", Map.of("X-Sib-Sandbox", "drop"));
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
        .timeout(Duration.ofSeconds(15)).header("api-key", props.apiKey())
        .header("accept", "application/json").header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status < 200 || status >= 300)
        throw new DeliveryFailure("brevo_http_" + status, status == 408 || status == 429 || status >= 500);
      try {
        String messageId = json.readTree(response.body()).path("messageId").asString();
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException();
        return messageId;
      } catch (RuntimeException e) { throw new DeliveryFailure("brevo_invalid_response", false); }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeliveryFailure("interrupted", true);
    } catch (java.io.IOException e) {
      throw new DeliveryFailure("network_error", true);
    }
  }

  public static class DeliveryFailure extends RuntimeException {
    public final boolean retryable;
    public DeliveryFailure(String code, boolean retryable) { super(code); this.retryable = retryable; }
  }
}
