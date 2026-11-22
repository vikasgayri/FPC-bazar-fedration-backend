package org.fpcbazaar.api.enquiries;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A message from one of the enquiry forms (contact, quote, sourcing, connect, ...). Status: new, replied, closed. */
@Document("enquiries")
@CompoundIndex(name = "form_created", def = "{'formKey': 1, 'createdAt': -1}")
public class Enquiry {
  @Id private String id;
  private String formKey;
  private Map<String, String> data = new LinkedHashMap<>();
  private String lang;
  private String page;
  private String status = "new";
  private Instant createdAt;
  private String note;
  private Instant handledAt;
  private String handledBy;

  public String getId() { return id; }
  public String getFormKey() { return formKey; }
  public void setFormKey(String formKey) { this.formKey = formKey; }
  public Map<String, String> getData() { return data; }
  public void setData(Map<String, String> data) { this.data = data; }
  public String getLang() { return lang; }
  public void setLang(String lang) { this.lang = lang; }
  public String getPage() { return page; }
  public void setPage(String page) { this.page = page; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public Instant getHandledAt() { return handledAt; }
  public void setHandledAt(Instant handledAt) { this.handledAt = handledAt; }
  public String getHandledBy() { return handledBy; }
  public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
