package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.EmailStatus;
import com.relaydesk.channel.domain.OutboxEmail;
import com.relaydesk.channel.dto.OutboxRow;
import com.relaydesk.channel.repo.OutboxEmailRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local email mock: outbound mail is written to the outbox table as PENDING and "sent" by
 * the scheduled job (which just marks rows SENT and logs them).
 */
@Service
@Transactional
public class EmailOutboxService {

    private final OutboxEmailRepository repo;

    public EmailOutboxService(OutboxEmailRepository repo) {
        this.repo = repo;
    }

    public void enqueue(String to, String subject, String body) {
        OutboxEmail e = new OutboxEmail();
        e.setToAddress(to);
        e.setSubject(subject);
        e.setBody(body);
        e.setStatus(EmailStatus.PENDING);
        repo.save(e);
    }

    public Page<OutboxRow> recent(Pageable pageable) {
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return repo.findAll(pr).map(e -> new OutboxRow(e.getId(), e.getToAddress(), e.getSubject(),
                e.getStatus(), e.getSentAt(), e.getCreatedAt()));
    }
}
