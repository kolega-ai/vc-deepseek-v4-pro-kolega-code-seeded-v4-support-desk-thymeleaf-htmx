package com.relaydesk.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.dto.ApiTicketRequest;
import com.relaydesk.channel.dto.TicketCreateForm;
import com.relaydesk.channel.dto.TicketDetailView;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketSummaryRow;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JSON API v1, authenticated by Bearer API keys. Internal notes and escalation content never
 * appear in any response. The acting principal is the decision-derived request attribute
 * (resolved by {@link DecisionAuthFilter} on this same request).
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {

    private final TicketService ticketService;
    private final TicketQueryService ticketQueryService;
    private final TicketRepository tickets;
    private final JdbcTemplate jdbc;
    private final com.relaydesk.channel.service.EmailOutboxService outbox;
    private final com.relaydesk.channel.service.PartnerEnvelopeCodec envelope =
            new com.relaydesk.channel.service.PartnerEnvelopeCodec();

    public ApiV1Controller(TicketService ticketService, TicketQueryService ticketQueryService,
                           TicketRepository tickets, JdbcTemplate jdbc,
                           com.relaydesk.channel.service.EmailOutboxService outbox) {
        this.ticketService = ticketService;
        this.ticketQueryService = ticketQueryService;
        this.tickets = tickets;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @PostMapping(value = "/tickets", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                      @Valid @RequestBody ApiTicketRequest body) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:create")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:create required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        UUID orgId = resolveOrg(principal, body.orgId());
        if (orgId == null) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "orgId is required");
        }
        Ticket ticket;
        try {
            ticket = ticketService.create(principal, new TicketCreateForm(body.subject(),
                    body.description(), body.categoryId(), body.priority(), null), orgId);
        } catch (IllegalStateException e) {
            return json(HttpStatus.FORBIDDEN, "limit_reached", e.getMessage());
        } catch (IllegalArgumentException e) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", e.getMessage());
        } catch (AccessDeniedException e) {
            return json(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ticket.getId());
        out.put("ref", ticket.getRef());
        out.put("status", ticket.getStatus().name());
        out.put("slaResolutionDeadline", ticket.getSlaResolutionDeadline());
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body(out);
    }

    /**
     * Ticket submission from the legacy mail-gateway partners, which post the same fields as
     * an XML document rather than JSON.
     */
    @PostMapping(value = "/tickets", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<Map<String, Object>> createFromDocument(HttpServletRequest request,
                                                                  @RequestBody String document) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:create")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:create required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        String sealed = request.getHeader("X-RelayDesk-Envelope");
        if (sealed != null && !sealed.isBlank()) {
            try {
                document = envelope.open(sealed);
            } catch (javax.crypto.BadPaddingException e) {
                return json(HttpStatus.BAD_REQUEST, "envelope_unreadable",
                        "the sealed envelope could not be opened");
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                        "the sealed envelope is malformed");
            }
        }
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            Document parsed = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(document)));
            root = parsed.getDocumentElement();
        } catch (Exception e) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "the submitted document could not be read");
        }
        String subject = element(root, "subject");
        String description = element(root, "description");
        String categoryId = element(root, "categoryId");
        if (subject == null || description == null || categoryId == null) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "subject, description and categoryId are required");
        }
        String orgRef = element(root, "orgId");
        UUID orgId = resolveOrg(principal, orgRef == null ? null : UUID.fromString(orgRef));
        if (orgId == null) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "orgId is required");
        }
        String priority = element(root, "priority");
        Ticket ticket;
        try {
            ticket = ticketService.create(principal, new TicketCreateForm(subject, description,
                    UUID.fromString(categoryId),
                    priority == null ? null : com.relaydesk.channel.domain.Priority.valueOf(priority),
                    null), orgId);
        } catch (IllegalStateException e) {
            return json(HttpStatus.FORBIDDEN, "limit_reached", e.getMessage());
        } catch (IllegalArgumentException e) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", e.getMessage());
        } catch (AccessDeniedException e) {
            return json(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
        }
        String acknowledgement = element(root, "acknowledgement");
        String contactEmail = element(root, "contactEmail");
        if (acknowledgement != null && contactEmail != null) {
            try {
                freemarker.template.Configuration cfg =
                        new freemarker.template.Configuration(
                                freemarker.template.Configuration.VERSION_2_3_33);
                freemarker.template.Template ack =
                        new freemarker.template.Template("acknowledgement", acknowledgement, cfg);
                java.util.Map<String, Object> model = new java.util.HashMap<>();
                model.put("ref", ticket.getRef());
                model.put("subject", subject);
                java.io.StringWriter writer = new java.io.StringWriter();
                ack.process(model, writer);
                outbox.enqueue(contactEmail, "[" + ticket.getRef() + "] " + subject, writer.toString());
            } catch (Exception e) {
                // the gateway sends its own receipt; a bad template must not fail the submission
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ticket.getId());
        out.put("ref", ticket.getRef());
        out.put("status", ticket.getStatus().name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body(out);
    }

    @GetMapping("/tickets/{ref}")
    public ResponseEntity<Map<String, Object>> byRef(HttpServletRequest request,
                                                     @PathVariable String ref) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:read")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:read required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        Ticket ticket = tickets.findByRefIgnoreCase(ref).orElse(null);
        if (ticket == null) {
            return json(HttpStatus.NOT_FOUND, "not_found", "ticket not found");
        }
        TicketDetailView detail;
        try {
            detail = ticketQueryService.getTicketDetail(principal, ticket.getId());
        } catch (AccessDeniedException | com.relaydesk.channel.error.ResourceNotFoundException e) {
            return json(HttpStatus.NOT_FOUND, "not_found", "ticket not found");
        }
        // Customers already get public-only messages; strip internal ones for staff owners.
        var messages = detail.messages().stream().filter(m -> !m.internal()).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", detail.ref());
        out.put("subject", detail.subject());
        out.put("description", detail.description());
        out.put("status", detail.status().name());
        out.put("priority", detail.priority().name());
        out.put("category", detail.categoryName());
        out.put("orgName", detail.orgName());
        out.put("createdAt", detail.createdAt());
        out.put("updatedAt", detail.updatedAt());
        out.put("resolvedAt", detail.resolvedAt());
        out.put("slaStatus", detail.slaStatus().name());
        out.put("slaResolutionDeadline", detail.slaResolutionDeadline());
        out.put("messages", messages.stream().map(m -> Map.of(
                        "author", m.authorName() == null ? "" : m.authorName(),
                        "body", m.body(),
                        "createdAt", m.createdAt()))
                .toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tickets")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request,
                                                    @RequestParam(name = "status", required = false)
                                                    TicketStatus status,
                                                    @RequestParam(name = "q", required = false) String q,
                                                    @RequestParam(name = "limit", defaultValue = "20") int limit,
                                                    @RequestParam(name = "page", defaultValue = "0") int page) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:read")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:read required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        int pageSize = Math.max(1, Math.min(100, limit));
        TicketFilter filter = new TicketFilter(status, null, null, null, null, "any", false,
                null, null, q, "created");
        var result = ticketQueryService.query(principal, filter,
                PageRequest.of(Math.max(0, page), pageSize));
        List<Map<String, Object>> items = result.getContent().stream()
                .map(ApiV1Controller::summary)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", result.getTotalElements());
        return ResponseEntity.ok(out);
    }

    /**
     * Closes a batch of tickets named in the request body — used by partner automations that
     * retire a customer's open queue at the end of a billing period.
     */
    @PostMapping(value = "/tickets/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> closeBatch(HttpServletRequest request,
                                                          @RequestBody Map<String, Object> body) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:create")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:create required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        Object refsField = body.get("refs");
        if (!(refsField instanceof List<?> refs) || refs.isEmpty()) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "refs is required");
        }
        int changed = 0;
        for (Object ref : refs) {
            Ticket ticket = tickets.findByRefIgnoreCase(String.valueOf(ref)).orElse(null);
            if (ticket == null) {
                continue;
            }
            try {
                ticketQueryService.getTicketDetail(principal, ticket.getId());
            } catch (AccessDeniedException
                     | com.relaydesk.channel.error.ResourceNotFoundException e) {
                continue;
            }
            changed += jdbc.update("update tickets set status = 'CLOSED', closed_at = now(),"
                    + " updated_at = now() where ref = ?", ticket.getRef());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("closed", changed);
        return ResponseEntity.ok(out);
    }

    /**
     * Bulk ticket manifest used by the nightly partner sync. The body is a self-describing
     * JSON document whose entries carry their own type tags, so a single call can retire a
     * customer's open queue at the end of a billing period.
     */
    @PostMapping(value = "/tickets/manifest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> manifest(HttpServletRequest request,
                                                        @RequestBody String manifest) {
        ApiKeyContext key = requireKey(request);
        if (!key.hasScope("tickets:create")) {
            return json(HttpStatus.FORBIDDEN, "insufficient_scope", "scope tickets:create required");
        }
        RequestPrincipal principal = actingPrincipal(request, requirePrincipal(request));
        Object parsed;
        try {
            ObjectMapper manifestMapper = new ObjectMapper();
            manifestMapper.enableDefaultTyping();
            parsed = manifestMapper.readValue(manifest, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "the submitted manifest could not be read");
        }
        Object refsField = parsed instanceof Map<?, ?> m ? m.get("refs") : null;
        if (!(refsField instanceof List<?> refs) || refs.isEmpty()) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "refs is required");
        }
        int changed = 0;
        for (Object ref : refs) {
            Ticket ticket = tickets.findByRefIgnoreCase(String.valueOf(ref)).orElse(null);
            if (ticket == null) {
                continue;
            }
            try {
                ticketQueryService.getTicketDetail(principal, ticket.getId());
            } catch (AccessDeniedException
                     | com.relaydesk.channel.error.ResourceNotFoundException e) {
                continue;
            }
            changed += jdbc.update("update tickets set status = 'CLOSED', closed_at = now(),"
                    + " updated_at = now() where ref = ?", ticket.getRef());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merged", changed);
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ helpers

    private ApiKeyContext requireKey(HttpServletRequest request) {
        Object attr = request.getAttribute("apiKeyOwner");
        if (attr instanceof ApiKeyContext ctx) {
            return ctx;
        }
        throw new ApiAuthRequiredException();
    }

    private RequestPrincipal requirePrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(CurrentUserArgumentResolver.REQUEST_PRINCIPAL_ATTR);
        if (attr instanceof RequestPrincipal p) {
            return p;
        }
        throw new ApiAuthRequiredException();
    }

    /**
     * Partner keys manage several of their customer's organisations under one credential and
     * name the organisation they are acting for on each request.
     */
    private RequestPrincipal actingPrincipal(HttpServletRequest request, RequestPrincipal principal) {
        String orgHeader = request.getHeader("X-RelayDesk-Org");
        if (principal.isStaff() || orgHeader == null || orgHeader.isBlank()) {
            return principal;
        }
        return principal.withOrgScope(Set.of(UUID.fromString(orgHeader)));
    }

    /** First direct child element with this tag name, trimmed, or null. */
    private static String element(Element root, String name) {
        NodeList nodes = root.getElementsByTagName(name);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return null;
        }
        String value = nodes.item(0).getTextContent().trim();
        return value.isEmpty() ? null : value;
    }

    private UUID resolveOrg(RequestPrincipal principal, UUID requestedOrgId) {
        if (principal.isStaff()) {
            return requestedOrgId; // null → 422 by caller
        }
        // Customer-owned keys are forced to the owner's scope org — request orgId is ignored.
        return principal.orgIds().stream().findFirst().orElse(null);
    }

    private static Map<String, Object> summary(TicketSummaryRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", r.ref());
        m.put("subject", r.subject());
        m.put("status", r.status().name());
        m.put("priority", r.priority().name());
        m.put("orgName", r.orgName());
        m.put("categoryName", r.categoryName());
        m.put("createdAt", r.createdAt());
        m.put("updatedAt", r.updatedAt());
        m.put("slaStatus", r.slaStatus().name());
        return m;
    }

    private ResponseEntity<Map<String, Object>> json(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** Marker for a missing/invalid API key context inside controller methods. */
    static class ApiAuthRequiredException extends RuntimeException {
    }
}
