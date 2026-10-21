package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByRefIgnoreCase(String ref);

    Optional<Ticket> findTopByOrderByCreatedAtDesc();

    long countByStatus(TicketStatus status);

    long countByStatusAndTeamId(TicketStatus status, UUID teamId);

    long countByStatusAndRequesterId(TicketStatus status, UUID requesterId);

    long countByRequesterIdAndStatusIn(UUID requesterId, List<TicketStatus> openStatuses);

    long countByAssigneeIdAndStatusIn(UUID assigneeId, List<TicketStatus> openStatuses);

    List<Ticket> findByStatusIn(List<TicketStatus> statuses);

    List<Ticket> findByStatusAndResolvedAtBefore(TicketStatus status, Instant cutoff);

    @Query("select count(t) from Ticket t where t.status in :statuses and t.slaStatus = com.relaydesk.channel.domain.SlaStatus.BREACHED")
    long countBreached(List<TicketStatus> statuses);
}
