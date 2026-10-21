package com.relaydesk.core.repo;

import com.relaydesk.core.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    long countByOrgId(UUID orgId);
}
