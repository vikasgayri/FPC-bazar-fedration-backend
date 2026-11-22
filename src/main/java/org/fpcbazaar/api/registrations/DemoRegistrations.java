package org.fpcbazaar.api.registrations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.users.User;
import org.fpcbazaar.api.users.UserRepository;
import org.fpcbazaar.api.users.UserWrites;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SEED_DEMO=true only (laptop testing): a few sample registrations, marked {@code sample: true},
 * and the demo "vendor" / "fpo" logins linked to their own record. Never enable on the live server.
 */
@Component
@Order(2)
public class DemoRegistrations implements ApplicationRunner {
  private final AppProperties props;
  private final RegistrationRepository repo;
  private final UserRepository users;
  private final Clock clock;
  private final UserWrites writes;

  public DemoRegistrations(AppProperties props, RegistrationRepository repo, UserRepository users, Clock clock,
      UserWrites writes) {
    this.writes = writes;
    this.props = props;
    this.repo = repo;
    this.users = users;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.seedDemo() || repo.existsBySlug("aravali-bio-inputs")) return;
    Instant now = Instant.now(clock);
    Registration vendor = repo.save(rec("vendor", Status.VERIFIED, "rajasthan", "Aravali Bio Inputs", "aravali-bio-inputs", "", now, 50,
        Map.of("gst", "08ABCDE1234F1Z5", "turnover", "1.2 crore", "productCategory", "inputs", "supplyState", "rajasthan",
            "district", "Udaipur", "contact", "Sunil Joshi", "phone", "9876500004",
            "about", "Vermicompost and organic bio-fertilisers made from gaushala cow dung.",
            "productList", "Vermicompost\nGaushala organic fertiliser\nPanchgavya liquid",
            "pastWork", "Supplied 120 tonnes of vermicompost to 6 FPOs in Mewar.")));
    Registration fpo = repo.save(rec("fpo", Status.VERIFIED, "rajasthan", "Hadoti Kisan Producer Company", null, "", now, 40,
        Map.of("district", "Baran", "cin", "U01100RJ2020PTC000001", "kisanCount", "850", "odop", "Coriander",
            "contact", "Ramesh Meena", "phone", "9876500001")));
    repo.saveAll(List.of(
        rec("fpo", Status.PENDING, "gujarat", "Saurashtra Kisan FPO", null, "", now, 3,
            Map.of("district", "Rajkot", "cin", "U01100GJ2021PTC000002", "kisanCount", "420", "odop", "Cumin",
                "contact", "Hiren Patel", "phone", "9876500002")),
        rec("vendor", Status.REJECTED, "gujarat", "GreenLeaf Traders", "greenleaf-traders", "GST number could not be verified.", now, 25,
            Map.of("gst", "24ABCDE1234F1Z5", "turnover", "30 lakh", "productCategory", "produce", "supplyState", "gujarat",
                "district", "Surat", "contact", "Neha Shah", "phone", "9876500006")),
        rec("ngo", Status.REJECTED, "mp", "Gram Vikas Seva Samiti", null, "Registration documents are incomplete (80G / 12A missing).",
            now, 30, Map.of("district", "Indore", "contact", "Kailash Verma", "phone", "9876500007"))));
    link("vendor", vendor.getId());
    link("fpo", fpo.getId());
  }

  private void link(String username, String recordId) {
    users.findByUsername(username).filter((u) -> u.getRecordId() == null)
        .ifPresent((User u) -> writes.apply(u.getId(), new Update().set("recordId", recordId)));
  }

  private static Registration rec(String kind, Status status, String state, String name, String slug, String reason, Instant now,
      int daysAgo, Map<String, String> data) {
    Registration r = new Registration();
    r.setKind(kind);
    r.setStatus(status);
    r.setState(state);
    r.setName(name);
    r.setSlug(slug);
    r.setReason(reason);
    r.setData(new LinkedHashMap<>(data));
    r.setSample(true);
    r.setLang("en");
    r.setCreatedAt(now.minus(Duration.ofDays(daysAgo)));
    if (status != Status.PENDING) {
      r.setDecidedAt(now.minus(Duration.ofDays(Math.max(0, daysAgo - 6))));
      r.setDecidedBy("Demo Admin");
    }
    return r;
  }
}
