package com.relaydesk.channel.job;

import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Auto-closes RESOLVED tickets whose resolution is older than 72 hours (via the state machine). */
@Component
public class TicketAutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(TicketAutoCloseJob.class);

    private final TicketRepository tickets;
    private final TicketService ticketService;

    public TicketAutoCloseJob(TicketRepository tickets, TicketService ticketService) {
        this.tickets = tickets;
        this.ticketService = ticketService;
    }

    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 120_000)
    public void run() {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Ticket> resolved = tickets.findByStatusAndResolvedAtBefore(TicketStatus.RESOLVED, cutoff);
        int closed = 0;
        for (Ticket t : resolved) {
            try {
                ticketService.changeStatus(RequestPrincipal.system(), t.getId(),
                        TicketStatus.CLOSED, null);
                closed++;
            } catch (RuntimeException e) {
                log.warn("auto-close failed for ticket {}: {}", t.getRef(), e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("auto-closed {} resolved tickets", closed);
        }
    }
}
