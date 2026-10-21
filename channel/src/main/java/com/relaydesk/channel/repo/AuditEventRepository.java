package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

/**
 * Note: only insert paths are ever used (append-only). There is deliberately no
 * update/delete endpoint for audit records anywhere in the application.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    List<AuditEvent> findByResourceIdOrderByOccurredAtAsc(String resourceId);
    List<AuditEvent> findTop20ByOrderByOccurredAtDesc();
}
