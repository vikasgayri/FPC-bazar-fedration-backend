package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fpcbazaar.api.audit.AuditService;
import org.fpcbazaar.api.common.ApiException;
import org.fpcbazaar.api.notifications.Notifications;
import org.fpcbazaar.api.registrations.RegistrationRepository;
import org.fpcbazaar.api.security.*;
import org.fpcbazaar.api.users.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;

class LastAdminConcurrencyTest {
  @Test void twoAdminsCannotSimultaneouslyDisableTheLastTwoAccounts() throws Exception {
    UserRepository repo=mock(UserRepository.class);
    UserWrites writes=mock(UserWrites.class);
    User a=mock(User.class), b=mock(User.class);
    Map<String,User> users=Map.of("a",a,"b",b);
    Set<String> active=ConcurrentHashMap.newKeySet(); active.addAll(users.keySet());
    for(var entry:users.entrySet()) {
      String id=entry.getKey(); User u=entry.getValue();
      when(u.getId()).thenReturn(id); when(u.getUsername()).thenReturn(id); when(u.getRole()).thenReturn(Role.ADMIN);
      when(u.isActive()).thenAnswer(inv -> active.contains(id));
      when(repo.findById(id)).thenReturn(java.util.Optional.of(u));
    }
    when(repo.countByRoleAndActiveTrue(Role.ADMIN)).thenAnswer(inv -> (long)active.size());
    when(writes.apply(anyString(),any(Update.class))).thenAnswer(inv -> {
      // Widen the race: without serialization both callers see two active admins before either update.
      Thread.sleep(100); String id=inv.getArgument(0); active.remove(id); return users.get(id);
    });
    var service=new UserAdminService(repo,mock(RegistrationRepository.class),mock(MongoTemplate.class),
        mock(PasswordEncoder.class),mock(AuditService.class),writes,mock(Notifications.class));
    var gate=new java.util.concurrent.CountDownLatch(1);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one=pool.submit(() -> { gate.await(); return disable(service,"a","b"); });
      var two=pool.submit(() -> { gate.await(); return disable(service,"b","a"); });
      gate.countDown(); assertThat(java.util.List.of(one.get(),two.get())).containsExactlyInAnyOrder(true,false);
    }
    assertThat(active).hasSize(1);
  }
  private boolean disable(UserAdminService service,String actor,String target) {
    try { service.update(new CurrentUser(actor,actor,actor,Role.ADMIN,null,null),target,
        new UserAdminService.UpdateRequest(null,null,false)); return true; }
    catch(ApiException expected) { return false; }
  }
}
