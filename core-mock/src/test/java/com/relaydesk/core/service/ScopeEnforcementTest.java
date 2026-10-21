package com.relaydesk.core.service;

import com.relaydesk.core.domain.Organization;
import com.relaydesk.core.dto.OrgCreateForm;
import com.relaydesk.core.repo.ContactRepository;
import com.relaydesk.core.repo.EntitlementRepository;
import com.relaydesk.core.repo.OrganizationRepository;
import com.relaydesk.core.repo.WebhookReceiptRepository;
import com.relaydesk.core.web.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the scope enforcement in {@link CoreService} (mocked repositories, no DB). */
class ScopeEnforcementTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ContactRepository contacts = mock(ContactRepository.class);
    private final EntitlementRepository entitlements = mock(EntitlementRepository.class);
    private final WebhookReceiptRepository receipts = mock(WebhookReceiptRepository.class);
    private final CoreService service = new CoreService(organizations, contacts, entitlements, receipts);

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private ScopeContext customerOf(UUID... ids) {
        return new ScopeContext(UUID.randomUUID(), "CUSTOMER_USER", List.of(ids));
    }

    private ScopeContext staff(String role) {
        return new ScopeContext(UUID.randomUUID(), role, List.of());
    }

    private Organization org(UUID id) {
        Organization org = new Organization();
        org.setId(id);
        return org;
    }

    @Test
    void customerReadsOwnOrg() {
        Organization own = org(orgA);
        when(organizations.findById(orgA)).thenReturn(Optional.of(own));
        assertSame(own, service.requireOrg(customerOf(orgA), orgA));
    }

    @Test
    void customerReadingForeignOrgIsDenied() {
        when(organizations.findById(orgB)).thenReturn(Optional.of(org(orgB)));
        assertThrows(ScopeDeniedException.class, () -> service.requireOrg(customerOf(orgA), orgB));
    }

    @Test
    void customerListContainsOnlyOwnOrg() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(organizations.findByIdIn(List.of(orgA), pageable))
                .thenReturn(new PageImpl<>(List.of(org(orgA)), pageable, 1));
        Page<Organization> result = service.listOrgs(customerOf(orgA), null, pageable);
        assertEquals(1, result.getTotalElements());
        assertEquals(orgA, result.getContent().get(0).getId());
        verify(organizations).findByIdIn(List.of(orgA), pageable);
    }

    @Test
    void agentCanReadAnyOrg() {
        when(organizations.findById(orgB)).thenReturn(Optional.of(org(orgB)));
        assertNotNull(service.requireOrg(staff("SUPPORT_AGENT"), orgB));
    }

    @Test
    void agentCannotCreateOrg() {
        assertThrows(ScopeDeniedException.class,
                () -> service.createOrg(staff("SUPPORT_AGENT"), new OrgCreateForm("X Corp", "Growth", null)));
        verify(organizations, never()).save(any());
    }

    @Test
    void adminCanCreateOrg() {
        when(organizations.existsBySlug(anyString())).thenReturn(false);
        when(organizations.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        Organization created = service.createOrg(staff("SUPPORT_ADMIN"), new OrgCreateForm("X Corp", "Growth", null));
        assertEquals("x-corp", created.getSlug());
        assertEquals("ACTIVE", created.getStatus());
        assertNotNull(created.getId());
        verify(organizations).save(any(Organization.class));
    }
}
