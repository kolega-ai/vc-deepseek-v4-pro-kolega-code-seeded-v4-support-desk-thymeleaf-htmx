package com.relaydesk.channel.job;

import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.service.SlaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Re-evaluates SLA status for every active ticket (breach transitions notify + audit). */
@Component
public class SlaMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitorJob.class);

    private final TicketRepository tickets;
    private final SlaService sla;

    public SlaMonitorJob(TicketRepository tickets, SlaService sla) {
        this.tickets = tickets;
        this.sla = sla;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        List<Ticket> active = tickets.findByStatusIn(List.of(
                TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
                TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED));
        active.forEach(sla::evaluate);
        log.info("sla-monitor evaluated {} active tickets", active.size());
    }
}
