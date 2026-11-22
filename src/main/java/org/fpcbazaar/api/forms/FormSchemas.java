package org.fpcbazaar.api.forms;

import static org.fpcbazaar.api.forms.FormField.area;
import static org.fpcbazaar.api.forms.FormField.email;
import static org.fpcbazaar.api.forms.FormField.file;
import static org.fpcbazaar.api.forms.FormField.number;
import static org.fpcbazaar.api.forms.FormField.select;
import static org.fpcbazaar.api.forms.FormField.tel;
import static org.fpcbazaar.api.forms.FormField.text;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server copy of every public form (frontend: {@code src/config/forms.js}, {@code docs/ADMIN_API.md},
 * {@code docs/BUSINESS_CONNECT_FORMS.md}). If a form changes in the frontend, change it here too.
 * Forms in {@link #REGISTRATION_KINDS} create a registration (Pending / Verified / Rejected);
 * all other forms are stored as enquiries.
 */
public final class FormSchemas {
  private FormSchemas() {}

  /** State keys used by the frontend (17 states network + J&K). */
  public static final Set<String> STATES = Set.of("rajasthan", "gujarat", "mp", "maharashtra", "chhattisgarh", "jharkhand",
      "odisha", "haryana", "punjab", "up", "uttarakhand", "goa", "andhra", "telangana", "tn", "karnataka", "puducherry", "jk");

  public static final Set<String> PRODUCT_CATEGORIES = Set.of("grains", "produce", "spices", "dairy", "inputs", "processed", "equipment", "feed");
  public static final Set<String> PROJECT_TYPES = Set.of("dairy", "greenhouse", "bazar");

  public static final Set<String> NGO_TYPES = Set.of("trust", "society", "section8");

  /** formKey -> registration kind. */
  public static final Map<String, String> REGISTRATION_KINDS = Map.of(
      "fpo", "fpo", "vendor", "vendor", "ngo", "ngo", "projecthead", "projecthead", "member", "member", "job", "job");
  public static final Set<String> KINDS = Set.of("fpo", "vendor", "ngo", "projecthead", "member", "job");

  private static final Map<String, List<FormField>> FORMS = Map.ofEntries(
      Map.entry("contact", List.of(text("name", true), tel("phone", true), email("email", false), text("state", false),
          area("message", true))),
      Map.entry("register", List.of(text("name", true), text("organisation", false), text("village", false),
          text("district", true), text("state", true), tel("phone", true),
          select("activity", true, Set.of("farmer", "fpo", "dairy", "shg", "gaushala")), text("count", false))),
      Map.entry("eauto", List.of(text("name", true), number("age", true, 18, 60), tel("phone", true), text("district", true),
          select("education", false, Set.of("below10", "10", "12", "grad")),
          select("licence", true, Set.of("yes", "applied", "no")), text("area", false))),
      Map.entry("amul", List.of(text("name", true), tel("phone", true), text("city", true), text("space", false),
          text("invest", false), select("loan", false, Set.of("yes", "maybe", "no")))),
      Map.entry("investor", List.of(text("name", true), text("organisation", false),
          select("interest", true, Set.of("cbg", "ppp", "csr", "other")), text("ticket", false), tel("phone", true),
          email("email", true), area("message", false))),
      Map.entry("product", List.of(text("name", true), tel("phone", true), number("cattle", true, 1, 100_000),
          text("location", true))),
      Map.entry("quote", List.of(text("subject", true), text("name", true), tel("phone", true), text("organisation", false),
          text("quantity", false), text("state", false), area("message", false))),
      Map.entry("supplier", List.of(text("organisation", true), text("name", true), tel("phone", true), email("email", false),
          select("supplierType", true, Set.of("farmer", "fpo", "manufacturer", "supplier")), text("state", true),
          text("district", false), text("categories", true), text("capacity", false), area("message", false))),
      Map.entry("sourcing", List.of(text("need", true), text("quantity", true),
          select("frequency", false, Set.of("once", "monthly", "seasonal", "contract")), text("destination", false),
          text("spec", false), text("name", true), text("organisation", false), tel("phone", true), email("email", false),
          area("message", false))),
      Map.entry("connect", List.of(select("role", true, Set.of("farmer", "fpo", "manufacturer", "supplier", "buyer")),
          text("name", true), text("organisation", false), tel("phone", true), email("email", false), text("state", true),
          area("offer", true))),
      // Registrations (multipart when files are attached)
      Map.entry("fpo", List.of(text("fpoName", true), select("state", true, STATES), text("district", true), text("cin", true),
          number("kisanCount", true, 1, 1_000_000), text("odop", false), text("name", true), tel("phone", true),
          email("email", true), file("certificate", true), file("pan", true), file("boardResolution", true))),
      Map.entry("vendor", List.of(text("companyName", true), text("gst", true), text("turnover", true),
          select("productCategory", true, PRODUCT_CATEGORIES), select("supplyState", true, STATES),
          select("state", true, STATES), text("district", false), text("name", true), tel("phone", true), email("email", true),
          area("about", false), area("productList", false), area("pastWork", false), file("logo", false),
          file("certificate", true))),
      // NGO: registration certificate (Trust Deed / Society certificate / Section 8 incorporation), PAN, Darpan ID,
      // authorisation letter and ID of the person applying are required; tax and funding certificates are optional.
      Map.entry("ngo", List.of(text("ngoName", true), select("ngoType", true, NGO_TYPES), text("regNumber", true),
          text("darpanId", true), select("state", true, STATES), text("district", true),
          number("yearEstablished", false, 1850, 2100), text("focusArea", true), text("name", true), text("designation", false),
          tel("phone", true), email("email", true), area("about", false),
          file("certificate", true), file("pan", true), file("authorisation", true), file("idProof", true),
          file("cert12ab", false), file("cert80g", false), file("csr1", false), file("fcra", false), file("financials", false))),
      Map.entry("projecthead", List.of(text("name", true), tel("phone", true), email("email", false), text("experience", true),
          select("state", true, STATES), select("projectType", true, PROJECT_TYPES), area("message", false))),
      Map.entry("member", List.of(text("name", true), tel("phone", true), email("email", false), select("state", true, STATES),
          text("organisation", false), file("feeSlip", true))),
      Map.entry("job", List.of(text("name", true), tel("phone", true), email("email", true), text("position", true),
          text("location", true), text("qualification", false), file("resume", true), area("message", false))));

  public static List<FormField> get(String formKey) { return formKey == null ? null : FORMS.get(formKey); }

  public static boolean isRegistration(String formKey) { return REGISTRATION_KINDS.containsKey(formKey); }
}
