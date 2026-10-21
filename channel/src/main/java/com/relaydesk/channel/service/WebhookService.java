package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.DeliveryStatus;
import com.relaydesk.channel.domain.WebhookDeliveryAttempt;
import com.relaydesk.channel.domain.WebhookEndpoint;
import com.relaydesk.channel.dto.WebhookAttemptRow;
import com.relaydesk.channel.dto.WebhookForm;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.WebhookDeliveryAttemptRepository;
import com.relaydesk.channel.repo.WebhookEndpointRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Webhook endpoints and delivery attempts. Payloads passed to {@link #publish} must contain
 * PUBLIC ticket fields only — internal notes and escalation reasons are never published.
 */
@Service
@Transactional
public class WebhookService {

    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "ticket.created", "ticket.status_changed", "ticket.replied", "escalation.resolved");

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryAttemptRepository attempts;
    private final AuditService audit;
    private final RestClient rest;

    public WebhookService(WebhookEndpointRepository endpoints,
                          WebhookDeliveryAttemptRepository attempts, AuditService audit,
                          RestClient.Builder builder) {
        this.endpoints = endpoints;
        this.attempts = attempts;
        this.audit = audit;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(factory).build();
    }

    public Page<WebhookEndpoint> endpoints(Pageable pageable) {
        return endpoints.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public List<WebhookEndpoint> all() {
        return endpoints.findAll();
    }

    public WebhookEndpoint create(RequestPrincipal admin, WebhookForm f) {
        requireAdmin(admin);
        List<String> events = validateEvents(f.events());
        WebhookEndpoint e = new WebhookEndpoint();
        e.setName(f.name());
        e.setUrl(f.url());
        e.setSecret(f.secret());
        e.setEvents(String.join(",", events));
        endpoints.save(e);
        audit.record(admin, "webhook.created", "webhook", e.getId().toString(), e.getName(),
                null, e.getUrl() + " events=" + e.getEvents());
        return e;
    }

    public WebhookEndpoint update(RequestPrincipal admin, UUID id, WebhookForm f) {
        requireAdmin(admin);
        WebhookEndpoint e = load(id);
        String before = e.getUrl() + " events=" + e.getEvents();
        List<String> events = validateEvents(f.events());
        e.setName(f.name());
        e.setUrl(f.url());
        e.setSecret(f.secret());
        e.setEvents(String.join(",", events));
        endpoints.save(e);
        audit.record(admin, "webhook.updated", "webhook", id.toString(), e.getName(),
                before, e.getUrl() + " events=" + e.getEvents());
        return e;
    }

    public void delete(RequestPrincipal admin, UUID id) {
        requireAdmin(admin);
        WebhookEndpoint e = load(id);
        endpoints.delete(e);
        audit.record(admin, "webhook.deleted", "webhook", id.toString(), e.getName(),
                e.getName(), null);
    }

    /**
     * Records a PENDING delivery attempt for every enabled endpoint subscribed to the event.
     * The dispatch job performs the actual deliveries.
     */
    public void publish(String event, String payloadJson) {
        for (WebhookEndpoint e : endpoints.findAll()) {
            if (!e.isEnabled()) {
                continue;
            }
            if (Arrays.asList(e.getEvents().split(",")).contains(event)) {
                WebhookDeliveryAttempt a = new WebhookDeliveryAttempt();
                a.setEndpointId(e.getId());
                a.setEvent(event);
                a.setPayload(payloadJson);
                a.setStatus(DeliveryStatus.PENDING);
                attempts.save(a);
            }
        }
    }

    public Page<WebhookAttemptRow> attempts(Pageable pageable) {
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WebhookDeliveryAttempt> page = attempts.findAll(pr);
        Map<UUID, String> names = endpoints.findAll().stream()
                .collect(Collectors.toMap(WebhookEndpoint::getId, WebhookEndpoint::getName));
        return page.map(a -> new WebhookAttemptRow(a.getId(), names.get(a.getEndpointId()),
                a.getEvent(), a.getStatus(), a.getResponseCode(), a.getErrorMessage(),
                a.getCreatedAt()));
    }

    /**
     * Sends a ping to the endpoint straight away so the admin sees the outcome on the page
     * instead of waiting for the next dispatch run. The attempt is recorded either way.
     */
    public void fireTest(RequestPrincipal admin, UUID id) {
        requireAdmin(admin);
        WebhookEndpoint ep = load(id);
        WebhookDeliveryAttempt a = new WebhookDeliveryAttempt();
        a.setEndpointId(id);
        a.setEvent("ping");
        a.setPayload("{}");
        try {
            HttpStatusCode rsp = rest.post()
                    .uri(ep.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-RelayDesk-Event", "ping")
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            a.setStatus(DeliveryStatus.SUCCEEDED);
            a.setResponseCode(rsp.value());
            a.setDeliveredAt(Instant.now());
        } catch (RuntimeException e) {
            a.setStatus(DeliveryStatus.FAILED);
            a.setErrorMessage(e.getMessage() == null ? "ping failed"
                    : e.getMessage().substring(0, Math.min(500, e.getMessage().length())));
        }
        attempts.save(a);
    }

    private List<String> validateEvents(List<String> events) {
        List<String> list = events == null ? List.of() : events;
        if (list.isEmpty()) {
            throw new IllegalArgumentException("at least one webhook event is required");
        }
        for (String ev : list) {
            if (!ALLOWED_EVENTS.contains(ev)) {
                throw new IllegalArgumentException("unknown webhook event: " + ev);
            }
        }
        return list;
    }

    private void requireAdmin(RequestPrincipal p) {
        if (p == null || !p.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
    }

    private WebhookEndpoint load(UUID id) {
        return endpoints.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("webhook endpoint not found"));
    }
}
