package com.relaydesk.core.service;

import com.relaydesk.core.domain.Contact;
import com.relaydesk.core.domain.Entitlement;
import com.relaydesk.core.domain.Organization;
import com.relaydesk.core.domain.WebhookReceipt;
import com.relaydesk.core.dto.ContactCreateForm;
import com.relaydesk.core.dto.ContactUpdateForm;
import com.relaydesk.core.dto.OrgCreateForm;
import com.relaydesk.core.dto.OrgUpdateForm;
import com.relaydesk.core.repo.ContactRepository;
import com.relaydesk.core.repo.EntitlementRepository;
import com.relaydesk.core.repo.OrganizationRepository;
import com.relaydesk.core.repo.WebhookReceiptRepository;
import com.relaydesk.core.web.ScopeContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business rules of the vendor CRM stub. Every scope decision is derived from the verified
 * {@link ScopeContext} (i.e. the scope token) — never from a raw scope/role/org parameter;
 * callers cannot pass identity into these methods.
 */
@Service
public class CoreService {

    private final OrganizationRepository organizations;
    private final ContactRepository contacts;
    private final EntitlementRepository entitlements;
    private final WebhookReceiptRepository receipts;

    public CoreService(OrganizationRepository organizations, ContactRepository contacts,
                       EntitlementRepository entitlements, WebhookReceiptRepository receipts) {
        this.organizations = organizations;
        this.contacts = contacts;
        this.entitlements = entitlements;
        this.receipts = receipts;
    }

    // ---- reads ----

    public Page<Organization> listOrgs(ScopeContext scope, String q, Pageable pageable) {
        if (scope.isCustomer()) {
            List<UUID> ids = scope.orgIds();
            if (ids.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
            return organizations.findByIdIn(ids, pageable);
        }
        if (q == null || q.isBlank()) {
            return organizations.findAll(pageable);
        }
        return organizations.search(q, pageable);
    }

    /** Customers may only touch orgs listed in their token's {@code orgs} claim. */
    public Organization requireOrg(ScopeContext scope, UUID orgId) {
        Organization org = organizations.findById(orgId)
                .orElseThrow(() -> new OrgNotFoundException("organization " + orgId + " not found"));
        if (scope.isCustomer() && !scope.orgIds().contains(orgId)) {
            throw new ScopeDeniedException("token scope does not include organization " + orgId);
        }
        return org;
    }

    public long contactCount(UUID orgId) {
        return contacts.countByOrgId(orgId);
    }

    public List<Contact> contactsFor(UUID orgId) {
        return contacts.findByOrgIdOrderByCreatedAtAsc(orgId);
    }

    public List<Entitlement> entitlementsFor(ScopeContext scope, UUID orgId) {
        if (scope.isCustomer() && !scope.orgIds().contains(orgId)) {
            throw new ScopeDeniedException("token scope does not include organization " + orgId);
        }
        return entitlements.findByOrgIdOrderByIdAsc(orgId);
    }

    /** Entitlement listing is a staff-only surface. */
    public List<Entitlement> allEntitlements(ScopeContext scope, UUID orgId) {
        requireStaff(scope);
        return orgId == null ? entitlements.findAll() : entitlements.findByOrgIdOrderByIdAsc(orgId);
    }

    // ---- mutations (admin only) ----

    public Organization createOrg(ScopeContext scope, OrgCreateForm form) {
        requireAdmin(scope);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName(form.name());
        org.setPlan(form.plan());
        org.setAddress(form.address());
        org.setStatus("ACTIVE");
        org.setSlug(uniqueSlug(slugify(form.name())));
        org.setCreatedAt(Instant.now());
        return organizations.save(org);
    }

    public Organization updateOrg(ScopeContext scope, UUID orgId, OrgUpdateForm form) {
        requireAdmin(scope);
        Organization org = organizations.findById(orgId)
                .orElseThrow(() -> new OrgNotFoundException("organization " + orgId + " not found"));
        org.setName(form.name());
        org.setPlan(form.plan());
        org.setAddress(form.address());
        org.setStatus(form.status());
        return organizations.save(org);
    }

    public Contact createContact(ScopeContext scope, UUID orgId, ContactCreateForm form) {
        requireAdmin(scope);
        organizations.findById(orgId)
                .orElseThrow(() -> new OrgNotFoundException("organization " + orgId + " not found"));
        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setOrgId(orgId);
        contact.setFirstName(form.firstName());
        contact.setLastName(form.lastName());
        contact.setEmail(form.email());
        contact.setPhone(form.phone());
        contact.setTitle(form.title());
        contact.setActive(true);
        contact.setCreatedAt(Instant.now());
        return contacts.save(contact);
    }

    public Contact updateContact(ScopeContext scope, UUID contactId, ContactUpdateForm form) {
        requireAdmin(scope);
        Contact contact = contacts.findById(contactId)
                .orElseThrow(() -> new ContactNotFoundException("contact " + contactId + " not found"));
        contact.setFirstName(form.firstName());
        contact.setLastName(form.lastName());
        contact.setEmail(form.email());
        contact.setPhone(form.phone());
        contact.setTitle(form.title());
        contact.setActive(form.active());
        return contacts.save(contact);
    }

    public void storeReceipt(WebhookReceipt receipt) {
        receipts.save(receipt);
    }

    // ---- guards & helpers ----

    private void requireAdmin(ScopeContext scope) {
        if (!scope.isAdmin()) {
            throw new ScopeDeniedException("role " + scope.role() + " cannot mutate vendor-core data");
        }
    }

    private void requireStaff(ScopeContext scope) {
        if (!scope.isStaff()) {
            throw new ScopeDeniedException("role " + scope.role() + " cannot read entitlements");
        }
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (organizations.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** Lowercases and replaces runs of non-alphanumeric characters with a single dash. */
    public static String slugify(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "org" : slug;
    }
}
