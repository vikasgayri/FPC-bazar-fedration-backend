package org.fpcbazaar.api.users;

import org.fpcbazaar.api.config.AppProperties;
import org.fpcbazaar.api.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup: creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD if no admin exists yet,
 * and (only when SEED_DEMO=true, for local testing) the demo logins the frontend README mentions.
 */
@Component
@Order(1)
public class UserBootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AppProperties props;

  public UserBootstrap(UserRepository users, PasswordEncoder encoder, AppProperties props) {
    this.users = users;
    this.encoder = encoder;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!users.existsByRole(Role.ADMIN)) {
      AppProperties.Admin a = props.admin();
      if (a == null || isBlank(a.username()) || isBlank(a.password())) {
        log.warn("No admin user exists. Set ADMIN_USERNAME and ADMIN_PASSWORD and restart to create one.");
      } else if (a.password().length() < 10) {
        log.warn("ADMIN_PASSWORD must be at least 10 characters; admin user not created.");
      } else if (a.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
        log.warn("ADMIN_PASSWORD is longer than 72 bytes; admin user not created. Use a shorter password.");
      } else {
        users.save(new User(a.username(), encoder.encode(a.password()), Role.ADMIN, a.name(), null, null));
        log.info("Created admin user '{}'.", a.username().trim().toLowerCase(java.util.Locale.ROOT));
      }
    }
    if (props.seedDemo()) seedDemo();
  }

  private void seedDemo() {
    demo("admin", "admin123", Role.ADMIN, "Demo Admin", null);
    demo("rajasthan-head", "state123", Role.STATE_HEAD, "Rajasthan State Head", "rajasthan");
    demo("project-head", "project123", Role.PROJECT_HEAD, "Project Head (Rajasthan)", "rajasthan");
    demo("vendor", "vendor123", Role.VENDOR, "Demo Vendor", "rajasthan");
    demo("fpo", "fpo123", Role.FPO, "Demo FPO", "rajasthan");
    log.warn("SEED_DEMO=true: demo logins are active. Never enable this on the live server.");
  }

  private void demo(String username, String password, Role role, String name, String state) {
    if (users.existsByUsername(username)) return;
    users.save(new User(username, encoder.encode(password), role, name, state, null));
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
