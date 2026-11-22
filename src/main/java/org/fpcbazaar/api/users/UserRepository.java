package org.fpcbazaar.api.users;

import java.util.Optional;
import org.fpcbazaar.api.security.Role;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {
  Optional<User> findByUsername(String username);
  boolean existsByRole(Role role);
  boolean existsByUsername(String username);
  Optional<User> findFirstByRecordId(String recordId);
  long countByRoleAndActiveTrue(Role role);
}
