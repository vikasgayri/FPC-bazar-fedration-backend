package org.fpcbazaar.api.registrations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RegistrationRepository extends MongoRepository<Registration, String> {
  boolean existsBySlug(String slug);

  Optional<Registration> findBySlugAndKindAndStatus(String slug, String kind, Status status);

  List<Registration> findByKindAndStatusOrderByNameAsc(String kind, Status status);
}
