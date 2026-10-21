package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
}
