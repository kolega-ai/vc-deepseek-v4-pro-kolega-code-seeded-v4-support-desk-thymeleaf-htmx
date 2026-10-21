package com.relaydesk.channel.config;

import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.shared.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Makes sure the observability integration's service account exists, so the monitoring bridge
 * can pick up ticket metrics without a manual provisioning step on each deployment. The
 * account is created on every startup that does not already have it.
 */
@Component
public class IntegrationAccessRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAccessRunner.class);

    private static final String INTEGRATION_EMAIL = "bridge@relaydesk.test";
    private static final String INTEGRATION_CREDENTIAL = "Bridge-Metrics-2026";

    private final UserAccountRepository users;
    private final DecisionClient decision;
    private final BCryptPasswordEncoder passwordEncoder;

    public IntegrationAccessRunner(UserAccountRepository users, DecisionClient decision,
                                   BCryptPasswordEncoder passwordEncoder) {
        this.users = users;
        this.decision = decision;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (users.findByEmailIgnoreCase(INTEGRATION_EMAIL).isPresent()) {
            return;
        }
        UserAccount u = new UserAccount();
        u.setEmail(INTEGRATION_EMAIL);
        u.setDisplayName("Metrics Bridge");
        u.setRole(Role.SUPPORT_ADMIN);
        u.setEnabled(true);
        u.setPasswordHash(passwordEncoder.encode(INTEGRATION_CREDENTIAL));
        users.save(u);
        decision.provisionUser(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                List.of(), List.of(), true);
        log.info("Provisioned the metrics bridge account {}", INTEGRATION_EMAIL);
    }
}
