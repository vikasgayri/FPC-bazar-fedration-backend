package org.fpcbazaar.api.registrations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fpcbazaar.api.files.StoredFile;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A registration from one of the public forms: FPO, vendor (company), project head, member, job, NGO.
 * New records start as PENDING. Every decision is kept in {@code history} (audit trail).
 */
@Document("registrations")
@CompoundIndex(name = "kind_status_created", def = "{'kind': 1, 'status': 1, 'createdAt': -1}")
@CompoundIndex(name = "state_kind", def = "{'state': 1, 'kind': 1}")
// Database-level guard: one pending/verified record per CIN (FPO), GST (vendor) or Darpan ID (NGO),
// even when two requests arrive at the same moment.
@CompoundIndex(name = "unique_active", def = "{'uniqueKey': 1}", unique = true,
    partialFilter = "{'uniqueKey': {'$exists': true}, 'status': {'$in': ['PENDING', 'VERIFIED']}}")
public class Registration {
  @Id private String id;
  @Version private Long version;
  private String kind;
  private Status status = Status.PENDING;
  private String reason = "";
  private String state;
  private String name;
  /** Public URL part for vendors: /vendor/{slug}. */
  @Indexed(unique = true, sparse = true) private String slug;
  private Map<String, String> data = new LinkedHashMap<>();
  /** kind + CIN / GST / Darpan ID, e.g. "fpo:U01100RJ2020PTC000001" (see RegistrationService.uniqueKey). */
  private String uniqueKey;
  private Map<String, StoredFile> files = new LinkedHashMap<>();
  private String lang;
  private String page;
  private boolean sample;
  private Instant createdAt;
  private Instant decidedAt;
  private String decidedBy;
  private String decidedById;
  private List<HistoryEntry> history = new ArrayList<>();

  public record HistoryEntry(Instant at, String byId, String byName, Status from, Status to, String reason) {}

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public Long getVersion() { return version; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public Status getStatus() { return status; }
  public void setStatus(Status status) { this.status = status; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getSlug() { return slug; }
  public void setSlug(String slug) { this.slug = slug; }
  public Map<String, String> getData() { return data; }
  public void setData(Map<String, String> data) { this.data = data; }
  public String getUniqueKey() { return uniqueKey; }
  public void setUniqueKey(String uniqueKey) { this.uniqueKey = uniqueKey; }
  public Map<String, StoredFile> getFiles() { return files; }
  public void setFiles(Map<String, StoredFile> files) { this.files = files; }
  public String getLang() { return lang; }
  public void setLang(String lang) { this.lang = lang; }
  public String getPage() { return page; }
  public void setPage(String page) { this.page = page; }
  public boolean isSample() { return sample; }
  public void setSample(boolean sample) { this.sample = sample; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getDecidedAt() { return decidedAt; }
  public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
  public String getDecidedBy() { return decidedBy; }
  public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
  public String getDecidedById() { return decidedById; }
  public void setDecidedById(String decidedById) { this.decidedById = decidedById; }
  public List<HistoryEntry> getHistory() { return history; }
  public void setHistory(List<HistoryEntry> history) { this.history = history; }
}
