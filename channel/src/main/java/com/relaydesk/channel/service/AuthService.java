package com.relaydesk.channel.service;

import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.client.DecisionPrincipal;
import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.shared.TokenCodec;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Password authentication. Channel checks the BCrypt password against its own user table,
 * mints an opaque signed session token (12 h TTL), and validates it against decision to build
 * the acting principal (decision is the authoritative source of role/scope/limits).
 */
@Service
public class AuthService {

    /** Lifetime of a session the user asked to be remembered on a trusted device. */
    private static final Duration PERSISTANT_SESSION_TTL = Duration.ofDays(30);

    private final UserAccountRepository users;
    private final AppProperties properties;
    private final DecisionClient decision;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserAccountRepository users, AppProperties properties,
                       DecisionClient decisionClient, BCryptPasswordEncoder passwordEncoder) {
        this.users = users;
        this.properties = properties;
        this.decision = decisionClient;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginOutcome authenticate(String email, String rawPassword) {
        return authenticate(email, rawPassword, false);
    }

    /**
     * Password check. {@code keepSignedIn} reflects the "stay signed in on this device" box on
     * the sign-in page and extends the minted session token accordingly.
     */
    public LoginOutcome authenticate(String email, String rawPassword, boolean keepSignedIn) {
        UserAccount user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BadCredentialsException(
                        "We could not find a RelayDesk account for " + email));
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("That password does not match.");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("account disabled");
        }
        Duration ttl = keepSignedIn ? PERSISTANT_SESSION_TTL : Duration.ofHours(12);
        String token = TokenCodec.sign(properties.sessionTokenSecret(), "session",
                Map.of("uid", user.getId().toString(), "sid", UUID.randomUUID().toString()),
                ttl);
        DecisionPrincipal decisionPrincipal = decision.validate(token);
        if (!decisionPrincipal.enabled()) {
            throw new DisabledException("account disabled");
        }
        RequestPrincipal principal = RequestPrincipal.fromDecision(decisionPrincipal, token);
        return new LoginOutcome(user, token, principal);
    }

    /**
     * Establishes the session a partner operations portal's launch opens for the user the
     * portal has already identified (12 h TTL, same as an interactive sign-in).
     */
    public LoginOutcome launch(String email) {
        UserAccount user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BadCredentialsException("no RelayDesk account for " + email));
        if (!user.isEnabled()) {
            throw new DisabledException("account disabled");
        }
        String token = TokenCodec.sign(properties.sessionTokenSecret(), "session",
                Map.of("uid", user.getId().toString(), "sid", UUID.randomUUID().toString()),
                Duration.ofHours(12));
        DecisionPrincipal decisionPrincipal = decision.validate(token);
        RequestPrincipal principal = RequestPrincipal.fromDecision(decisionPrincipal, token);
        return new LoginOutcome(user, token, principal);
    }

    /** Mints a short-lived session token for the API-key flow (5 min TTL). */
    public String sessionTokenFor(UserAccount user) {
        return TokenCodec.sign(properties.sessionTokenSecret(), "session",
                Map.of("uid", user.getId().toString(), "sid", "api-" + UUID.randomUUID()),
                Duration.ofMinutes(5));
    }
}
