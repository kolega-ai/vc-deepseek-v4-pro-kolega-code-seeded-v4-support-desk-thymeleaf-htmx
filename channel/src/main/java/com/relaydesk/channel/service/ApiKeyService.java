package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.ApiKey;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.ApiKeyForm;
import com.relaydesk.channel.dto.ApiKeyRow;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.ApiKeyRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.shared.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * API keys for the JSON API surface. Only the SHA-256 hash is stored; the plaintext is shown
 * exactly once at creation. Keys are owned by support users (never customers).
 */
@Service
@Transactional
public class ApiKeyService {

    public static final Set<String> ALLOWED_SCOPES = Set.of("tickets:create", "tickets:read");
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;
    private final UserAccountRepository users;
    private final AuditService audit;

    public ApiKeyService(ApiKeyRepository keys, UserAccountRepository users, AuditService audit) {
        this.keys = keys;
        this.users = users;
        this.audit = audit;
    }

    public ApiKeyCreated create(RequestPrincipal admin, ApiKeyForm f) {
        requireAdmin(admin);
        UserAccount owner = users.findById(f.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("api key owner not found"));
        if (owner.getRole() == Role.CUSTOMER_USER) {
            throw new IllegalArgumentException("api key owner must be a support user");
        }
        List<String> scopes = (f.scopes() == null || f.scopes().isEmpty())
                ? List.of("tickets:create", "tickets:read") : f.scopes();
        for (String s : scopes) {
            if (!ALLOWED_SCOPES.contains(s)) {
                throw new IllegalArgumentException("unknown api key scope: " + s);
            }
        }
        String raw = "rdk_" + randomBase62(32);
        ApiKey k = new ApiKey();
        k.setName(f.name());
        k.setKeyHash(Hashing.sha256Hex(raw));
        k.setPrefix(raw.substring(0, 10));
        k.setOwnerId(owner.getId());
        k.setScopes(String.join(",", scopes));
        if (f.expiresAt() != null) {
            k.setExpiresAt(f.expiresAt().atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC));
        }
        keys.save(k);
        audit.record(admin, "api_key.created", "api_key", k.getId().toString(), k.getName(),
                null, "scopes=" + k.getScopes());
        return new ApiKeyCreated(k, raw);
    }

    public Page<ApiKeyRow> keys(Pageable pageable) {
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ApiKey> page = keys.findAll(pr);
        Map<UUID, String> emails = users
                .findAllById(page.getContent().stream().map(ApiKey::getOwnerId).distinct().toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getEmail));
        return page.map(k -> new ApiKeyRow(k.getId(), k.getName(), k.getPrefix(),
                emails.get(k.getOwnerId()), k.getScopes(), k.isEnabled(), k.getExpiresAt(),
                k.getLastUsedAt(), k.getCreatedAt()));
    }

    public void revoke(RequestPrincipal admin, UUID id) {
        requireAdmin(admin);
        ApiKey k = keys.findById(id).orElseThrow(() -> new ResourceNotFoundException("api key not found"));
        k.setEnabled(false);
        keys.save(k);
        audit.record(admin, "api_key.revoked", "api_key", id.toString(), k.getName(),
                "enabled", "disabled");
    }

    /** Hash lookup, enabled and not expired; touches lastUsedAt on success. */
    public Optional<ApiKey> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKey> found = keys.findByKeyHash(Hashing.sha256Hex(rawKey));
        if (found.isEmpty() || !found.get().isEnabled()) {
            return Optional.empty();
        }
        ApiKey k = found.get();
        if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        k.setLastUsedAt(Instant.now());
        keys.save(k);
        return Optional.of(k);
    }

    private void requireAdmin(RequestPrincipal p) {
        if (p == null || !p.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
    }

    private static String randomBase62(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
