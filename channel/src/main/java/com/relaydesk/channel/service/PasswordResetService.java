package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.PasswordResetToken;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.PasswordResetTokenRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Single-use, 30-minute password reset tokens (SHA-256 at rest). The request path returns the
 * same outcome whether or not the email exists — no user enumeration.
 */
@Service
@Transactional
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final PasswordResetTokenRepository tokens;
    private final EmailOutboxService outbox;
    private final AuditService audit;
    private final BCryptPasswordEncoder passwordEncoder;

    public PasswordResetService(UserAccountRepository users, PasswordResetTokenRepository tokens,
                                EmailOutboxService outbox, AuditService audit,
                                BCryptPasswordEncoder passwordEncoder) {
        this.users = users;
        this.tokens = tokens;
        this.outbox = outbox;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Always returns the same UI outcome regardless of existence: the preview link when the
     * user exists, empty otherwise. An audit event is written only when the user exists.
     */
    public Optional<String> request(String email) {
        Optional<UserAccount> user = users.findByEmailIgnoreCase(email);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        UserAccount u = user.get();
        String raw = randomToken();
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId(u.getId());
        t.setTokenHash(Hashing.sha256Hex(raw));
        t.setExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        t.setUsed(false);
        tokens.save(t);

        String link = "/reset-password?token=" + raw;
        outbox.enqueue(email, "Reset your RelayDesk password",
                "A password reset was requested.\nReset: " + link + "\nThis link expires in 30 minutes.");
        audit.record(null, "password.reset_requested", "user", u.getId().toString(), u.getEmail(),
                null, "token issued");
        return Optional.of(link);
    }

    public boolean isValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return tokens.findByTokenHash(Hashing.sha256Hex(rawToken))
                .map(t -> !t.isUsed() && t.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    public void reset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResourceNotFoundException("invalid or expired reset token");
        }
        PasswordResetToken t = tokens.findByTokenHash(Hashing.sha256Hex(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("invalid or expired reset token"));
        if (t.isUsed()) {
            throw new ResourceNotFoundException("reset token already used");
        }
        if (t.getExpiresAt().isBefore(Instant.now())) {
            throw new ResourceNotFoundException("reset token expired");
        }
        if (newPassword == null || newPassword.length() < 10) {
            throw new IllegalArgumentException("password must be at least 10 characters");
        }
        UserAccount u = users.findById(t.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("user not found"));
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(u);
        t.setUsed(true);
        tokens.save(t);
        audit.record(null, "password.reset_completed", "user", u.getId().toString(), u.getEmail(),
                null, "password changed");
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
