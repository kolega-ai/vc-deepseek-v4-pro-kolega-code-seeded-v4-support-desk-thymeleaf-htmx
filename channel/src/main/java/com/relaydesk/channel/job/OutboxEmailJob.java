package com.relaydesk.channel.job;

import com.relaydesk.channel.domain.EmailStatus;
import com.relaydesk.channel.domain.OutboxEmail;
import com.relaydesk.channel.repo.OutboxEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Local email mock: marks PENDING outbox rows SENT (no external SMTP service). */
@Component
public class OutboxEmailJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxEmailJob.class);

    private final OutboxEmailRepository outbox;

    public OutboxEmailJob(OutboxEmailRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void run() {
        List<OutboxEmail> pending = outbox.findTop20ByStatusOrderByCreatedAtAsc(EmailStatus.PENDING);
        for (OutboxEmail email : pending) {
            email.setStatus(EmailStatus.SENT);
            email.setSentAt(Instant.now());
            outbox.save(email);
            log.info("outbox mock-sent email id={} to={}", email.getId(), email.getToAddress());
        }
    }
}
