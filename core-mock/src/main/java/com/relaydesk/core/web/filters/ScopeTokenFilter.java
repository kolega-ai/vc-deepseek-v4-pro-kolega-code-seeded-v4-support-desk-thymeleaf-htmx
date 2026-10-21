package com.relaydesk.core.web.filters;

import com.relaydesk.core.config.CoreProperties;
import com.relaydesk.core.web.Problem;
import com.relaydesk.core.web.ScopeContext;
import com.relaydesk.shared.TokenCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Verifies the decision-minted scope token ({@code X-Scope-Token}) on every {@code /api/**}
 * call and stores the verified identity as the {@code scopeContext} request attribute.
 *
 * <p>Identity is taken exclusively from the verified token: any user id, role, or scope
 * fields supplied elsewhere in the request are ignored by construction. A missing, tampered,
 * or expired token terminates the request with {@code CORE_1003}. {@code /webhooks/sink}
 * and {@code /api/health} are open by design.
 */
@Component
public class ScopeTokenFilter extends OncePerRequestFilter {

    private final CoreProperties properties;

    public ScopeTokenFilter(CoreProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI(); // standalone MockMvc leaves servletPath empty
        }
        if ("/webhooks/sink".equals(path) || "/api/health".equals(path) || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("X-Scope-Token");
        if (token == null || token.isBlank()) {
            Problem.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "CORE_1003", "invalid scope token", "missing X-Scope-Token header");
            return;
        }
        try {
            TokenCodec.Payload payload = TokenCodec.verify(properties.scopeTokenSecret(), "scope", token);
            UUID uid = UUID.fromString(payload.claim("uid"));
            String role = payload.claim("role");
            List<UUID> orgIds = parseUuids(payload.claim("orgs"));
            request.setAttribute("scopeContext", new ScopeContext(uid, role, orgIds));
            chain.doFilter(request, response);
        } catch (RuntimeException e) { // TokenException, unparseable claims, bad uuid, ...
            Problem.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "CORE_1003", "invalid scope token", e.getMessage());
        }
    }

    private static List<UUID> parseUuids(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
    }
}
