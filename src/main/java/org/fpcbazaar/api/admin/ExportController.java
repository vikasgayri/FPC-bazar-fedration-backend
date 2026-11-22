package org.fpcbazaar.api.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.enquiries.Enquiry;
import org.fpcbazaar.api.enquiries.EnquiryService;
import org.fpcbazaar.api.registrations.Registration;
import org.fpcbazaar.api.registrations.RegistrationService;
import org.fpcbazaar.api.security.Auth;
import org.fpcbazaar.api.security.CurrentUser;
import org.fpcbazaar.api.security.Role;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Excel (.xlsx) downloads with the same filters as the admin lists. Role scope applies:
 * a State Head gets only their state. Opened in a new tab, so {@code ?token=} is accepted here.
 */
@RestController
@RequestMapping("/api/admin/export")
public class ExportController {
  private static final int MAX_ROWS = 20_000;
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(INDIA);
  private static final Set<String> ENQUIRY_MAIN = Set.of("name", "phone", "email", "organisation", "state");

  private final RegistrationService registrations;
  private final EnquiryService enquiries;
  private final AuditService audit;
  private final Clock clock;

  public ExportController(RegistrationService registrations, EnquiryService enquiries, AuditService audit, Clock clock) {
    this.registrations = registrations;
    this.enquiries = enquiries;
    this.audit = audit;
    this.clock = clock;
  }

  private record Col<T>(String title, Function<T, Object> value) {}

  @GetMapping("/registrations")
  public void registrations(@RequestParam(required = false) String kind, @RequestParam(required = false) String status,
      @RequestParam(required = false) String state, @RequestParam(required = false) String q, HttpServletResponse res)
      throws IOException {
    CurrentUser u = Auth.current();
    if (u.role().scope() == Role.Scope.OWN) throw ApiException.forbidden("You are not allowed to do this.");
    List<Registration> rows = registrations.list(u, kind, status, state, q, MAX_ROWS);
    List<Col<Registration>> cols = List.of(
        new Col<>("Name", Registration::getName),
        new Col<>("Type", Registration::getKind),
        new Col<>("Status", (r) -> r.getStatus().code()),
        new Col<>("State", Registration::getState),
        new Col<>("District", (r) -> r.getData().get("district")),
        new Col<>("Contact person", (r) -> r.getData().get("contact")),
        new Col<>("Phone", (r) -> r.getData().get("phone")),
        new Col<>("Email", (r) -> r.getData().get("email")),
        new Col<>("CIN", (r) -> r.getData().get("cin")),
        new Col<>("GST", (r) -> r.getData().get("gst")),
        new Col<>("NGO Darpan ID", (r) -> r.getData().get("darpanId")),
        new Col<>("Kisan count", (r) -> r.getData().get("kisanCount")),
        new Col<>("Product category", (r) -> r.getData().get("productCategory")),
        new Col<>("Project type", (r) -> r.getData().get("projectType")),
        new Col<>("Reason (if rejected)", Registration::getReason),
        new Col<>("Applied on", (r) -> when(r.getCreatedAt())),
        new Col<>("Decided on", (r) -> when(r.getDecidedAt())),
        new Col<>("Decided by", Registration::getDecidedBy),
        new Col<>("Documents", (r) -> String.join(", ", r.getFiles().keySet())));
    write(res, "registrations", "Registrations", cols, rows);
    audit.log(u, "export_registrations", "export", null, rows.size() + " rows" + filters(kind, status, state, q));
  }

  @GetMapping("/enquiries")
  public void enquiries(@RequestParam(required = false) String formKey, @RequestParam(required = false) String status,
      @RequestParam(required = false) String q, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to, HttpServletResponse res) throws IOException {
    CurrentUser u = Auth.current();
    List<Enquiry> rows = enquiries.list(u, formKey, status, q, from, to, MAX_ROWS);
    List<Col<Enquiry>> cols = List.of(
        new Col<>("Form", Enquiry::getFormKey),
        new Col<>("Status", Enquiry::getStatus),
        new Col<>("Name", (e) -> e.getData().get("name")),
        new Col<>("Phone", (e) -> e.getData().get("phone")),
        new Col<>("Email", (e) -> e.getData().get("email")),
        new Col<>("Organisation", (e) -> e.getData().get("organisation")),
        new Col<>("State", (e) -> e.getData().get("state")),
        new Col<>("Details", ExportController::details),
        new Col<>("Received on", (e) -> when(e.getCreatedAt())),
        new Col<>("Note", Enquiry::getNote),
        new Col<>("Handled by", Enquiry::getHandledBy));
    write(res, "enquiries", "Enquiries", cols, rows);
    audit.log(u, "export_enquiries", "export", null, rows.size() + " rows" + filters(formKey, status, q, from));
  }

  private <T> void write(HttpServletResponse res, String file, String sheet, List<Col<T>> cols, List<T> rows) throws IOException {
    String name = "fpc-" + file + "-" + LocalDate.now(clock.withZone(INDIA)) + ".xlsx";
    res.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    res.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString());
    res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    try (OutputStream os = res.getOutputStream()) {
      Workbook wb = new Workbook(os, "FPC Bazaar Federation", "1.0");
      Worksheet ws = wb.newWorksheet(sheet);
      for (int c = 0; c < cols.size(); c++) ws.value(0, c, cols.get(c).title());
      ws.range(0, 0, 0, cols.size() - 1).style().bold().fillColor("E8F5E9").set();
      ws.freezePane(0, 1);
      int r = 1;
      for (T row : rows) {
        for (int c = 0; c < cols.size(); c++) {
          Object v = cols.get(c).value().apply(row);
          if (v != null && !v.toString().isEmpty()) ws.value(r, c, v.toString()); // always text, never a formula
        }
        r++;
      }
      for (int c = 0; c < cols.size(); c++) ws.width(c, 22);
      wb.finish();
    }
  }

  private static String details(Enquiry e) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, String> x : e.getData().entrySet()) {
      if (ENQUIRY_MAIN.contains(x.getKey())) continue;
      if (b.length() > 0) b.append(" | ");
      b.append(x.getKey()).append(": ").append(x.getValue());
    }
    return b.toString();
  }

  private static String when(Instant t) { return t == null ? null : WHEN.format(t); }

  private static String filters(String... f) {
    StringBuilder b = new StringBuilder();
    for (String s : f) if (s != null && !s.isBlank()) b.append(b.length() == 0 ? " (" : ", ").append(s);
    return b.length() == 0 ? "" : b.append(")").toString();
  }
}
