package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.TicketSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TicketSequenceRepository extends JpaRepository<TicketSequence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TicketSequence s order by s.id")
    Optional<TicketSequence> lockSequence();
}
