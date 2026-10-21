package com.relaydesk.decision.seed;

import com.relaydesk.decision.domain.PrincipalUser;
import com.relaydesk.decision.domain.RoleLimit;
import com.relaydesk.decision.repo.PrincipalUserRepository;
import com.relaydesk.decision.repo.RoleLimitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent bootstrap seed for the decision database.
 *
 * <p>The fixed-UUID admin matches the channel seeder's first admin: the session token channel
 * mints for {@code admin@relaydesk.test} validates against this row before any provisioning
 * call has happened.
 */
@Component
public class DecisionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DecisionSeeder.class);

    public static final UUID BOOTSTRAP_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final PrincipalUserRepository users;
    private final RoleLimitRepository roleLimits;

    public DecisionSeeder(PrincipalUserRepository users, RoleLimitRepository roleLimits) {
        this.users = users;
        this.roleLimits = roleLimits;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        PrincipalUser admin = new PrincipalUser();
        admin.setId(BOOTSTRAP_ADMIN_ID);
        admin.setEmail("admin@relaydesk.test");
        admin.setDisplayName("RelayDesk Administrator");
        admin.setRole("SUPPORT_ADMIN");
        admin.setEnabled(true);
        users.save(admin);
        seedLimit("CUSTOMER_USER", "max_open_tickets", 10);
        seedLimit("CUSTOMER_USER", "max_attachment_mb", 10);
        seedLimit("CUSTOMER_USER", "max_attachments_per_ticket", 3);
        seedLimit("SUPPORT_AGENT", "max_claimed_tickets", 20);
        log.info("Seeded bootstrap admin {} and role limits", BOOTSTRAP_ADMIN_ID);
    }

    private void seedLimit(String role, String limitKey, int limitValue) {
        RoleLimit limit = new RoleLimit();
        limit.setRole(role);
        limit.setLimitKey(limitKey);
        limit.setLimitValue(limitValue);
        roleLimits.save(limit);
    }
}
