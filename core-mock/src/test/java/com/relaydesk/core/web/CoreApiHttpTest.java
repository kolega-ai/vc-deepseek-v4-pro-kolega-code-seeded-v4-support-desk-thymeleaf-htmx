package com.relaydesk.core.web;

import com.relaydesk.core.config.CoreProperties;
import com.relaydesk.core.service.CoreService;
import com.relaydesk.core.web.filters.InternalKeyFilter;
import com.relaydesk.core.web.filters.ScopeTokenFilter;
import com.relaydesk.shared.TokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests of the vendor-core authn boundary (standalone MockMvc incl. both filters):
 * internal service key, scope-token verification, and the deliberately open endpoints.
 */
class CoreApiHttpTest {

    private static final String SECRET = "test-scope-secret-0123456789";
    private static final String KEY = "test-internal-key";
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    private final CoreService core = mock(CoreService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CoreProperties properties = new CoreProperties(SECRET, KEY);
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(core))
                .addFilters(new InternalKeyFilter(properties), new ScopeTokenFilter(properties))
                .setControllerAdvice(new CoreExceptionHandler())
                .build();
    }

    private static String scopeToken(String role) {
        return TokenCodec.sign(SECRET, "scope", Map.of(
                "uid", UUID.randomUUID().toString(),
                "role", role,
                "orgs", "",
                "teams", "",
                "limits", ""), Duration.ofMinutes(1));
    }

    @Test
    void missingInternalKeyIs401Core1005() throws Exception {
        mvc.perform(get("/api/orgs").header("X-Scope-Token", scopeToken("SUPPORT_ADMIN")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE_1005"))
                .andExpect(jsonPath("$.message").value("invalid service key"));
    }

    @Test
    void tamperedScopeTokenIs403Core1003() throws Exception {
        String token = scopeToken("SUPPORT_ADMIN");
        String tampered = token.substring(0, token.length() - 3) + "abc";
        mvc.perform(get("/api/orgs").header("X-Internal-Key", KEY).header("X-Scope-Token", tampered))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE_1003"))
                .andExpect(jsonPath("$.message").value("invalid scope token"));
    }

    @Test
    void webhookSinkRequiresNoKeys() throws Exception {
        mvc.perform(post("/webhooks/sink")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ticket.created\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));
    }

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void validStaffTokenListsOrgs() throws Exception {
        when(core.listOrgs(any(), nullable(String.class), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mvc.perform(get("/api/orgs").header("X-Internal-Key", KEY).header("X-Scope-Token", scopeToken("SUPPORT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0));
    }
}
