package com.relaydesk.core.repo;

import com.relaydesk.core.domain.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Name search — bound parameter only, never concatenated user input. */
    @Query("select o from Organization o where lower(o.name) like lower(concat('%', :q, '%'))")
    List<Organization> findByNameContainingIgnoreCase(@Param("q") String q);

    /** Paginated variant of the same bound-parameter search. */
    @Query("select o from Organization o where lower(o.name) like lower(concat('%', :q, '%'))")
    Page<Organization> search(@Param("q") String q, Pageable pageable);

    Page<Organization> findByIdIn(List<UUID> ids, Pageable pageable);
}
