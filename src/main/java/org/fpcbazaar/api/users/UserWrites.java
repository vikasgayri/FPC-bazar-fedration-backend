package org.fpcbazaar.api.users;

import java.time.Instant;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Changes to existing logins are written as small atomic updates ($set / $inc of the changed fields only),
 * never by saving a whole User read earlier. So a sign-in can never undo a "switch off" made at the same moment,
 * and tokenVersion is only ever increased.
 */
@Component
public class UserWrites {
  private final MongoTemplate mongo;

  public UserWrites(MongoTemplate mongo) { this.mongo = mongo; }

  private static Query byId(String id) { return Query.query(Criteria.where("_id").is(id)); }

  public void touchLogin(String id, Instant at) {
    mongo.updateFirst(byId(id), new Update().set("lastLoginAt", at), User.class);
  }

  /** Applies the update and returns the login as it is afterwards (null if it no longer exists). */
  public User apply(String id, Update update) {
    return mongo.findAndModify(byId(id), update, FindAndModifyOptions.options().returnNew(true), User.class);
  }

  /**
   * Sets a new password chosen by the user, only if nothing changed the login since it was read
   * (no reset, switch-off or other password change in between). Returns null when that happened.
   */
  public User changeOwnPassword(String id, int expectedTokenVersion, String hash) {
    Query q = Query.query(Criteria.where("_id").is(id).and("tokenVersion").is(expectedTokenVersion).and("active").is(true));
    Update u = new Update().set("passwordHash", hash).set("mustChangePassword", false).inc("tokenVersion", 1);
    return mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), User.class);
  }
}
