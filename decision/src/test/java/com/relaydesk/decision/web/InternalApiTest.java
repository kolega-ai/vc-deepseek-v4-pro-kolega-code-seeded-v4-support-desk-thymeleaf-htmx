package com.relaydesk.decision.web;

import com.relaydesk.decision.config.DecisionProperties;
import com.relaydesk.decision.dto.PrincipalResponse;
import com.relaydesk.decision.service.AuthService;
import com.relaydesk.decision.service.ProvisionService;
import com.relaydesk.shared.TokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalApiTest {

    private static final String INTERNAL_KEY = "the-channel-internal-key";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AuthService authService = mock(AuthService.class);
    private final ProvisionService provisionService = mock(ProvisionService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(
                        new AuthController(authService),
                        new ProvisionController(provisionService),
                        new HealthController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new InternalKeyFilter(
                        new DecisionProperties("session-secret", "scope-secret", INTERNAL_KEY)))
                .build();
    }

    @Test
    void health_isOpenWithoutKey() throws Exception {
        mvc.perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void validate_withoutKey_returns401() throws Exception {
        mvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"some-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_service_key"));
    }

    @Test
    void validate_withWrongKey_returns401() throws Exception {
        mvc.perform(post("/internal/auth/validate")
                        .header("X-Internal-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"some-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_service_key"))
                .andExpect(jsonPath("$.message").value("missing or invalid service key"));
    }

    @Test
    void validate_withCorrectKeyAndValidToken_returnsPrincipal() throws Exception {
        PrincipalResponse principal = new PrincipalResponse(
                USER_ID, "agent@relaydesk.test", "Agent One", "SUPPORT_AGENT", true,
                List.of(), List.of(), Map.of("max_claimed_tickets", 20), "session-abc");
        when(authService.validate("good-token")).thenReturn(principal);

        mvc.perform(post("/internal/auth/validate")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"good-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("agent@relaydesk.test"))
                .andExpect(jsonPath("$.role").value("SUPPORT_AGENT"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.limits.max_claimed_tickets").value(20))
                .andExpect(jsonPath("$.sessionId").value("session-abc"));
    }

    @Test
    void validate_withCorrectKeyButInvalidToken_returns401InvalidToken() throws Exception {
        when(authService.validate("bad-token")).thenThrow(new TokenException("bad signature"));

        mvc.perform(post("/internal/auth/validate")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void scopeToken_withCorrectKey_returnsToken() throws Exception {
        when(authService.mintScopeToken("good-token"))
                .thenReturn(new com.relaydesk.decision.dto.ScopeTokenResponse("scope-token-value", 1_700_000_000L));

        mvc.perform(post("/internal/auth/scope-token")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"good-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("scope-token-value"))
                .andExpect(jsonPath("$.expiresAt").value(1_700_000_000L));
    }
}
