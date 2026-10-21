package com.relaydesk.core.seed;

import com.relaydesk.core.domain.Contact;
import com.relaydesk.core.domain.Entitlement;
import com.relaydesk.core.domain.Organization;
import com.relaydesk.core.repo.ContactRepository;
import com.relaydesk.core.repo.EntitlementRepository;
import com.relaydesk.core.repo.OrganizationRepository;
import com.relaydesk.core.service.CoreService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotent vendor-CRM seed: runs only when {@code organizations} is empty. Direct inserts
 * (no state machines exist in this external system). The 10 orgs + contacts are what the
 * channel seeder discovers later through the API.
 */
@Component
public class CoreSeeder implements ApplicationRunner {

    /** name / plan pairs (10 orgs). */
    private static final String[][] ORGS = {
            {"Acme Corp", "Enterprise"},
            {"Globex Inc.", "Growth"},
            {"Initech LLC", "Starter"},
            {"Umbrella Health", "Enterprise"},
            {"Northwind Traders", "Growth"},
            {"Stark Industries", "Enterprise"},
            {"Wayne Enterprises", "Enterprise"},
            {"Hooli", "Growth"},
            {"Pied Piper", "Starter"},
            {"Vandelay Industries", "Growth"}};

    private static final String[] FIRST = {"Jordan", "Priya", "Marcus", "Elena", "Sofia", "Devon",
            "Aiko", "Tomas", "Nadia", "Rafael", "Ingrid", "Sam"};
    private static final String[] LAST = {"Lee", "Patel", "Weber", "Rossi", "Chen", "Novak",
            "Sato", "Silva", "Haddad", "Okafor", "Lindqvist", "Byrne"};
    private static final String[] TITLES = {"IT Manager", "Operations Lead", "Finance Director", "Support Contact"};
    private static final String[] STREETS = {"Market Street", "Harbor Avenue", "Cedar Lane",
            "Industrial Boulevard", "Oak Avenue", "Lake Drive"};
    private static final String[] CITIES = {"Springfield", "Metropolis", "Smallville", "Gotham",
            "Star City", "Hill Valley"};

    private final OrganizationRepository organizations;
    private final ContactRepository contacts;
    private final EntitlementRepository entitlements;

    public CoreSeeder(OrganizationRepository organizations, ContactRepository contacts,
                      EntitlementRepository entitlements) {
        this.organizations = organizations;
        this.contacts = contacts;
        this.entitlements = entitlements;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (organizations.count() > 0) {
            return;
        }
        for (int i = 0; i < ORGS.length; i++) {
            Organization org = new Organization();
            org.setId(UUID.randomUUID());
            org.setName(ORGS[i][0]);
            org.setPlan(ORGS[i][1]);
            org.setStatus("ACTIVE");
            org.setSlug(CoreService.slugify(ORGS[i][0]));
            org.setAddress((i + 1) * 100 + " " + STREETS[i % STREETS.length] + ", " + CITIES[i % CITIES.length]);
            org.setCreatedAt(Instant.now());
            organizations.save(org);
            seedEntitlements(org, i);
            seedContacts(org, i);
        }
    }

    private void seedEntitlements(Organization org, int i) {
        addEntitlement(org, "lumina-cloud", 12 + (i * 11) % 109); // seats 12..120
        if (i % 3 != 2) {
            addEntitlement(org, "analytics", 8 + (i * 7) % 53);   // seats 8..60
        }
        if (i % 3 != 1) {
            addEntitlement(org, "sso-bundle", 10 + (i * 9) % 71); // seats 10..80
        }
    }

    private void addEntitlement(Organization org, String product, int seats) {
        Entitlement entitlement = new Entitlement();
        entitlement.setOrgId(org.getId());
        entitlement.setProduct(product);
        entitlement.setSeats(seats);
        entitlement.setStatus("ACTIVE");
        entitlements.save(entitlement);
    }

    /**
     * 3-4 contacts per org. Index arithmetic (i+j, i+3j) over the name lists guarantees
     * distinct first/last combinations — and therefore distinct emails — per org; the first
     * contact of Acme Corp is Jordan Lee, matching the documented demo login.
     */
    private void seedContacts(Organization org, int i) {
        int count = (i % 2 == 0) ? 4 : 3;
        for (int j = 0; j < count; j++) {
            String first = FIRST[(i + j) % FIRST.length];
            String last = LAST[(i + 3 * j) % LAST.length];
            Contact contact = new Contact();
            contact.setId(UUID.randomUUID());
            contact.setOrgId(org.getId());
            contact.setFirstName(first);
            contact.setLastName(last);
            contact.setEmail((first + "." + last).toLowerCase() + "@" + org.getSlug() + ".test");
            contact.setPhone("+1-555-01" + String.format("%02d", (i * 5 + j * 3 + 10) % 100));
            contact.setTitle(TITLES[j % TITLES.length]);
            contact.setActive(true);
            contact.setCreatedAt(Instant.now());
            contacts.save(contact);
        }
    }
}
