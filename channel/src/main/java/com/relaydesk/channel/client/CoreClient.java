package com.relaydesk.channel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.dto.ContactRow;
import com.relaydesk.channel.dto.OrgDetailRow;
import com.relaydesk.channel.dto.OrgSummaryRow;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for the external customer-core (core-mock). Every call mints a fresh scope
 * token via {@link DecisionClient} using the principal's session token, then presents
 * {@code X-Internal-Key} + {@code X-Scope-Token}. Problem+json responses are surfaced as
 * {@link CoreClientException} carrying the vendor error code.
 */
@Component
public final class CoreClient {

    private final RestClient rest;
    private final AppProperties properties;
    private final DecisionClient decision;
    private final ObjectMapper mapper;

    public CoreClient(RestClient.Builder builder, AppProperties properties,
                      DecisionClient decisionClient, ObjectMapper mapper) {
        this.properties = properties;
        this.decision = decisionClient;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(factory).baseUrl(properties.coreUrl()).build();
    }

    public Page<OrgSummaryRow> listOrgs(RequestPrincipal p, String q, int page, int size) {
        try {
            RestClient.RequestHeadersSpec<?> spec = rest.get()
                    .uri(b -> {
                        UriBuilder ub = b.path("/api/orgs")
                                .queryParam("page", page)
                                .queryParam("size", size);
                        if (q != null && !q.isBlank()) {
                            ub = ub.queryParam("q", q);
                        }
                        return ub.build();
                    });
            OrgPage result = withAuth(p, spec)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .body(OrgPage.class);
            List<OrgSummaryRow> content = result == null || result.items() == null ? List.of() : result.items();
            long total = result == null ? content.size() : result.total();
            return new PageImpl<>(content, PageRequest.of(page, size), total);
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    public OrgDetailRow org(RequestPrincipal p, UUID orgId) {
        try {
            return withAuth(p, rest.get().uri("/api/orgs/{id}", orgId))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .body(OrgDetailRow.class);
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    public List<OrgSummaryRow> allOrgs(RequestPrincipal p) {
        List<OrgSummaryRow> out = new ArrayList<>();
        int page = 0;
        while (page < 100) {
            Page<OrgSummaryRow> chunk = listOrgs(p, null, page, 100);
            out.addAll(chunk.getContent());
            if (chunk.getContent().size() < 100 || out.size() >= chunk.getTotalElements()) {
                break;
            }
            page++;
        }
        return out;
    }

    public OrgSummaryRow createOrg(RequestPrincipal p, String name, String plan, String address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (plan != null) {
            body.put("plan", plan);
        }
        if (address != null) {
            body.put("address", address);
        }
        try {
            return withAuth(p, rest.post()
                    .uri("/api/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .body(OrgSummaryRow.class);
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    public void updateOrg(RequestPrincipal p, UUID orgId, String name, String plan,
                          String address, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) {
            body.put("name", name);
        }
        if (plan != null) {
            body.put("plan", plan);
        }
        if (address != null) {
            body.put("address", address);
        }
        if (status != null) {
            body.put("status", status);
        }
        try {
            withAuth(p, rest.put()
                    .uri("/api/orgs/{id}", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .toBodilessEntity();
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    public ContactRow createContact(RequestPrincipal p, UUID orgId, String firstName,
                                    String lastName, String email, String phone, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", email);
        if (phone != null) {
            body.put("phone", phone);
        }
        if (title != null) {
            body.put("title", title);
        }
        try {
            return withAuth(p, rest.post()
                    .uri("/api/orgs/{id}/contacts", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .body(ContactRow.class);
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    public void updateContact(RequestPrincipal p, UUID contactId, String firstName,
                              String lastName, String email, String phone, String title,
                              boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (firstName != null) {
            body.put("firstName", firstName);
        }
        if (lastName != null) {
            body.put("lastName", lastName);
        }
        if (email != null) {
            body.put("email", email);
        }
        if (phone != null) {
            body.put("phone", phone);
        }
        if (title != null) {
            body.put("title", title);
        }
        body.put("active", active);
        try {
            withAuth(p, rest.put()
                    .uri("/api/contacts/{id}", contactId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleCoreError)
                    .toBodilessEntity();
        } catch (CoreClientException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new CoreClientException("CORE_1000", "customer core unreachable", 503);
        } catch (RestClientException e) {
            throw new CoreClientException("CORE_1000", "customer core call failed", 502);
        }
    }

    /** Adds the core service credential and a fresh decision-minted scope token. */
    private RestClient.RequestHeadersSpec<?> withAuth(RequestPrincipal p,
                                                      RestClient.RequestHeadersSpec<?> spec) {
        String scope = decision.mintScopeToken(p.sessionToken());
        return spec.header("X-Internal-Key", properties.coreInternalKey())
                .header("X-Scope-Token", scope);
    }

    /** Converts a problem+json error response into a {@link CoreClientException}. */
    private void handleCoreError(org.springframework.http.HttpRequest req,
                                 org.springframework.http.client.ClientHttpResponse res)
            throws IOException {
        int status = res.getStatusCode().value();
        String code = "CORE_1000";
        String message = "customer core error";
        try {
            Problem problem = mapper.readValue(res.getBody(), Problem.class);
            if (problem.code() != null) {
                code = problem.code();
            }
            if (problem.message() != null) {
                message = problem.message();
            } else if (problem.detail() != null) {
                message = problem.detail();
            }
        } catch (IOException ignored) {
            // keep defaults
        }
        throw new CoreClientException(code, message, status);
    }

    /** Wire records — the vendor's page response and problem+json body. */
    public record OrgPage(List<OrgSummaryRow> items, long total) {
    }

    record Problem(String code, String message, String detail) {
    }
}
