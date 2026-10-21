package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.ApiKey;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.service.ApiKeyService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.TicketService;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API surface authz tests (standalone MockMvc — no Spring context / DB):
 * missing key → 401, insufficient scope → 403, create with tickets:create → 201 with the
 * customer owner's org forced server-side.
 */
class ApiV1ControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    private final TicketService ticketService = mock(TicketService.class);
    private final TicketQueryService ticketQueryService = mock(TicketQueryService.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ApiV1Controller controller =
                new ApiV1Controller(ticketService, ticketQueryService, ticketRepository,
                        mock(org.springframework.jdbc.core.JdbcTemplate.class),
                        mock(com.relaydesk.channel.service.EmailOutboxService.class));
        RequestPrincipal customer = new RequestPrincipal(UUID.randomUUID(), "jordan.lee@acme.test",
                "Jordan Lee", Role.CUSTOMER_USER, Set.of(ORG), Set.of(), Map.of(), "s", "tok");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addFilters(new ApiKeyAuthFilter(apiKeyService),
                        (request, response, chain) -> {
                            request.setAttribute("requestPrincipal", customer);
                            chain.doFilter(request, response);
                        })
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body() {
        return "{\"orgId\":\"" + UUID.randomUUID() + "\",\"subject\":\"API ticket\","
                + "\"description\":\"created via the JSON API\",\"categoryId\":\"" + CATEGORY + "\"}";
    }

    private static ApiKey key(String scopes) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setOwnerId(UUID.randomUUID());
        k.setScopes(scopes);
        k.setEnabled(true);
        return k;
    }

    @Test
    void missingBearerHeaderIsRejectedWith401() throws Exception {
        mvc.perform(post("/api/v1/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));
    }

    @Test
    void readScopeCannotCreateTickets() throws Exception {
        when(apiKeyService.authenticate(any())).thenReturn(Optional.of(key("tickets:read")));
        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer rdk_testkey")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("insufficient_scope"));
    }

    @Test
    void createScopeCreatesTicketWithForcedOrg() throws Exception {
        when(apiKeyService.authenticate(any())).thenReturn(Optional.of(key("tickets:create")));
        Ticket created = new Ticket();
        created.setId(UUID.randomUUID());
        created.setRef("TCK-1001");
        created.setStatus(TicketStatus.NEW);
        created.setSlaResolutionDeadline(Instant.now());
        when(ticketService.create(any(), any(), eq(ORG))).thenReturn(created);

        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer rdk_testkey")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ref").value("TCK-1001"))
                .andExpect(jsonPath("$.status").value("NEW"));

        // The customer-owned key is forced to the owner's scope org — the request orgId is ignored.
        verify(ticketService).create(any(), any(), eq(ORG));
    }
}
