package com.relaydesk.channel.web;

import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.AttachmentService;
import com.relaydesk.channel.service.EscalationService;
import com.relaydesk.channel.service.NotificationService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.TicketService;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Desk route authorization: service-level {@link AccessDeniedException} (ticket outside the
 * agent's team scope) surfaces as 403 — plain text for htmx requests via the global handler.
 */
class DeskControllerAuthzTest {

    private final TicketQueryService ticketQueryService = mock(TicketQueryService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final EscalationService escalationService = mock(EscalationService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        DeskController controller = new DeskController(ticketQueryService, ticketService,
                escalationService, attachmentService, notificationService, teams, categories, users,
                mock(com.relaydesk.channel.service.UserAdminService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
        RequestPrincipal agent = new RequestPrincipal(UUID.randomUUID(), "agent.devon@relaydesk.test",
                "Devon Patel", Role.SUPPORT_AGENT, Set.of(), Set.of(UUID.randomUUID()),
                Map.of(), "sid", "token");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addFilters((request, response, chain) -> {
                    request.setAttribute("requestPrincipal", agent);
                    chain.doFilter(request, response);
                })
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void invisibleTicketYields403ForHtmx() throws Exception {
        when(ticketQueryService.getTicketDetail(any(), any()))
                .thenThrow(new AccessDeniedException("ticket is outside your team scope"));

        mvc.perform(get("/desk/tickets/{id}", UUID.randomUUID())
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Forbidden"));
    }

    @Test
    void invisibleTicketYields403ForPlainRequest() throws Exception {
        when(ticketQueryService.getTicketDetail(any(), any()))
                .thenThrow(new AccessDeniedException("ticket is outside your team scope"));

        mvc.perform(get("/desk/tickets/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
