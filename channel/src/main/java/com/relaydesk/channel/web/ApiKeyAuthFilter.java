package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.ApiKey;
import com.relaydesk.channel.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Stateless Bearer authentication for the JSON API surface (paths starting with /api/v1/).
 * API keys are looked up by SHA-256 hash; the key's owner's permissions are then resolved
 * through decision on this same request (no session is created).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";

    private final ApiKeyService apiKeys;

    public ApiKeyAuthFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WebPaths.path(request).startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = bearerToken(request.getHeader("Authorization"));
        Optional<ApiKey> key = apiKeys.authenticate(raw);
        if (key.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"error\":\"invalid_api_key\",\"message\":\"Missing or invalid API key\"}");
            return;
        }
        ApiKey k = key.get();
        request.setAttribute("apiKeyOwner",
                new ApiKeyContext(k.getId(), k.getOwnerId(), k.getScopes()));
        chain.doFilter(request, response);
    }

    private String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        int idx = header.indexOf(' ');
        if (idx <= 0 || !"Bearer".equalsIgnoreCase(header.substring(0, idx).trim())) {
            return null;
        }
        return header.substring(idx + 1).trim();
    }
}
