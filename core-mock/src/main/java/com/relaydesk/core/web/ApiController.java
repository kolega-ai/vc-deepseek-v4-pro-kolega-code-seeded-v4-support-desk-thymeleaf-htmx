package com.relaydesk.core.web;

import com.relaydesk.core.domain.Contact;
import com.relaydesk.core.domain.Organization;
import com.relaydesk.core.domain.WebhookReceipt;
import com.relaydesk.core.dto.ContactCreateForm;
import com.relaydesk.core.dto.ContactDto;
import com.relaydesk.core.dto.ContactUpdateForm;
import com.relaydesk.core.dto.EntitlementDto;
import com.relaydesk.core.dto.OrgCreateForm;
import com.relaydesk.core.dto.OrgDetail;
import com.relaydesk.core.dto.OrgSummary;
import com.relaydesk.core.dto.OrgUpdateForm;
import com.relaydesk.core.service.CoreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * External-system REST surface of "Lumina Customer Core".
 *
 * <p>Identity is taken exclusively from the verified scope token stored by
 * {@code ScopeTokenFilter} as the {@code scopeContext} request attribute. Any user id, role,
 * or scope fields present in query params, headers, or bodies are ignored by construction —
 * this controller has no input path for them.
 */
@RestController
public class ApiController {

    private final CoreService core;

    public ApiController(CoreService core) {
        this.core = core;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/orgs")
    public OrgPage listOrgs(@RequestAttribute("scopeContext") ScopeContext scope,
                            @RequestParam(name = "q", required = false) String q,
                            @RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 100);
        Page<Organization> result = core.listOrgs(scope, q, PageRequest.of(Math.max(page, 0), cappedSize));
        List<OrgSummary> items = result.getContent().stream()
                .map(o -> new OrgSummary(o.getId(), o.getName(), o.getSlug(), o.getPlan(), o.getStatus(),
                        (int) core.contactCount(o.getId())))
                .toList();
        return new OrgPage(items, result.getTotalElements());
    }

    @GetMapping("/api/orgs/{id}")
    public OrgDetail getOrg(@RequestAttribute("scopeContext") ScopeContext scope, @PathVariable UUID id) {
        Organization org = core.requireOrg(scope, id);
        List<EntitlementDto> entitlements = core.entitlementsFor(scope, id).stream()
                .map(e -> new EntitlementDto(e.getId(), e.getProduct(), e.getSeats(), e.getStatus()))
                .toList();
        List<ContactDto> contacts = core.contactsFor(id).stream().map(ApiController::toContactDto).toList();
        return new OrgDetail(org.getId(), org.getName(), org.getSlug(), org.getPlan(), org.getStatus(),
                org.getAddress(), entitlements, contacts);
    }

    @PostMapping("/api/orgs")
    public ResponseEntity<OrgSummary> createOrg(@RequestAttribute("scopeContext") ScopeContext scope,
                                                @Valid @RequestBody OrgCreateForm form) {
        Organization created = core.createOrg(scope, form);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrgSummary(created.getId(), created.getName(), created.getSlug(), created.getPlan(),
                        created.getStatus(), 0));
    }

    @PutMapping("/api/orgs/{id}")
    public OrgSummary updateOrg(@RequestAttribute("scopeContext") ScopeContext scope, @PathVariable UUID id,
                                @Valid @RequestBody OrgUpdateForm form) {
        Organization updated = core.updateOrg(scope, id, form);
        return new OrgSummary(updated.getId(), updated.getName(), updated.getSlug(), updated.getPlan(),
                updated.getStatus(), (int) core.contactCount(id));
    }

    @PostMapping("/api/orgs/{id}/contacts")
    public ResponseEntity<ContactDto> createContact(@RequestAttribute("scopeContext") ScopeContext scope,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody ContactCreateForm form) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toContactDto(core.createContact(scope, id, form)));
    }

    @PutMapping("/api/contacts/{id}")
    public ContactDto updateContact(@RequestAttribute("scopeContext") ScopeContext scope, @PathVariable UUID id,
                                    @Valid @RequestBody ContactUpdateForm form) {
        return toContactDto(core.updateContact(scope, id, form));
    }

    @GetMapping("/api/entitlements")
    public List<EntitlementDto> entitlements(@RequestAttribute("scopeContext") ScopeContext scope,
                                             @RequestParam(name = "orgId", required = false) UUID orgId) {
        return core.allEntitlements(scope, orgId).stream()
                .map(e -> new EntitlementDto(e.getId(), e.getProduct(), e.getSeats(), e.getStatus()))
                .toList();
    }

    /** Local webhook sink — deliberately unauthenticated (receives the channel's webhook deliveries). */
    @PostMapping("/webhooks/sink")
    public Map<String, Boolean> webhookSink(HttpServletRequest request,
                                            @RequestBody(required = false) String body) {
        StringBuilder headers = new StringBuilder();
        request.getHeaderNames().asIterator()
                .forEachRemaining(name -> headers.append(name).append(": ").append(request.getHeader(name)).append('\n'));
        WebhookReceipt receipt = new WebhookReceipt();
        receipt.setReceivedAt(Instant.now());
        receipt.setHeaders(headers.toString());
        receipt.setPayload(body == null ? "" : body);
        core.storeReceipt(receipt);
        return Map.of("received", true);
    }

    private static ContactDto toContactDto(Contact c) {
        return new ContactDto(c.getId(), c.getFirstName(), c.getLastName(), c.getEmail(), c.getPhone(),
                c.getTitle(), c.isActive());
    }

    /** Paged list response envelope. */
    public record OrgPage(List<OrgSummary> items, long total) {
    }
}
