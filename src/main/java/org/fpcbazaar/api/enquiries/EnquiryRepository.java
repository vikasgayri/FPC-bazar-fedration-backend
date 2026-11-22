package org.fpcbazaar.api.enquiries;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface EnquiryRepository extends MongoRepository<Enquiry, String> {
  long countByFormKey(String formKey);
  long countByStatus(String status);
}
