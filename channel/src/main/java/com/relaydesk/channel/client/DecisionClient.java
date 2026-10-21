package com.relaydesk.channel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.error.DecisionUnavailableException;
import com.relaydesk.channel.error.SessionInvalidException;
import com.relaydesk.shared.Role;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for the decision tier (the authorization authority).
 * All requests carry the {@code X-Internal-Key} service credential. Decision reads identity
 * ONLY from the signed session token; channel performs admin authorization checks before
 * calling the provisioning endpoints.
 */
@Component
public final class DecisionClient {

    private final RestClient rest;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public DecisionClient(RestClient.Builder builder, AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(factory).baseUrl(properties.decisionUrl()).build();
    }

    /**
     * Validates a session token against decision.
     * Transport errors / 5xx → {@link DecisionUnavailableException};
     * 401 (bad token), 403 (disabled), 404 (unknown user) → {@link SessionInvalidException}.
     */
    public DecisionPrincipal validate(String sessionToken) {
        try {
            return rest.post()
                    .uri("/internal/auth/validate")
                    .header("X-Internal-Key", properties.decisionInternalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", sessionToken))
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new SessionInvalidException("session invalid");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new SessionInvalidException("session disabled");
                    })
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new SessionInvalidException("session user unknown");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new DecisionUnavailableException(
                                "decision unavailable (HTTP " + res.getStatusCode().value() + ")");
                    })
                    .body(DecisionPrincipal.class);
        } catch (SessionInvalidException | DecisionUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new DecisionUnavailableException("decision unreachable", e);
        } catch (RestClientException e) {
            throw new DecisionUnavailableException("decision call failed", e);
        }
    }

    /** Mints a short-lived scope token via decision, for calls to the customer-core. */
    public String mintScopeToken(String sessionToken) {
        try {
            ScopeTokenResponse resp = rest.post()
                    .uri("/internal/auth/scope-token")
                    .header("X-Internal-Key", properties.decisionInternalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", sessionToken))
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new SessionInvalidException("session invalid");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new SessionInvalidException("session disabled");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new DecisionUnavailableException(
                                "decision unavailable (HTTP " + res.getStatusCode().value() + ")");
                    })
                    .body(ScopeTokenResponse.class);
            return resp.token();
        } catch (SessionInvalidException | DecisionUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new DecisionUnavailableException("decision unreachable", e);
        } catch (RestClientException e) {
            throw new DecisionUnavailableException("decision call failed", e);
        }
    }

    /** Provisions (or re-provisions) a user in decision. Caller must be an authorized admin. */
    public void provisionUser(UUID userId, String email, String displayName, Role role,
                              List<UUID> orgIds, List<UUID> teamIds, boolean enabled) {
        try {
            rest.post()
                    .uri("/internal/users")
                    .header("X-Internal-Key", properties.decisionInternalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ProvisionRequest(userId, email, displayName, role.name(),
                            orgIds == null ? List.of() : orgIds,
                            teamIds == null ? List.of() : teamIds, enabled))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DecisionUnavailableException(
                                "decision provisioning failed (HTTP " + res.getStatusCode().value() + ")");
                    })
                    .toBodilessEntity();
        } catch (DecisionUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new DecisionUnavailableException("decision unreachable", e);
        } catch (RestClientException e) {
            throw new DecisionUnavailableException("decision call failed", e);
        }
    }

    /** Partial update of a decision user; null fields are left unchanged. */
    public void updateUser(UUID userId, Role role, Boolean enabled, List<UUID> orgIds, List<UUID> teamIds) {
        ObjectNode body = mapper.createObjectNode();
        if (role != null) {
            body.put("role", role.name());
        }
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        if (orgIds != null) {
            body.set("orgIds", mapper.valueToTree(orgIds));
        }
        if (teamIds != null) {
            body.set("teamIds", mapper.valueToTree(teamIds));
        }
        try {
            rest.put()
                    .uri("/internal/users/{userId}", userId)
                    .header("X-Internal-Key", properties.decisionInternalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DecisionUnavailableException(
                                "decision update failed (HTTP " + res.getStatusCode().value() + ")");
                    })
                    .toBodilessEntity();
        } catch (DecisionUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new DecisionUnavailableException("decision unreachable", e);
        } catch (RestClientException e) {
            throw new DecisionUnavailableException("decision call failed", e);
        }
    }

    record ProvisionRequest(UUID userId, String email, String displayName, String role,
                            List<UUID> orgIds, List<UUID> teamIds, boolean enabled) {
    }

    record ScopeTokenResponse(String token, Long expiresAt) {
    }
}
