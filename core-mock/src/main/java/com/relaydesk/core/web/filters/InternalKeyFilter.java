package com.relaydesk.core.web.filters;

import com.relaydesk.core.config.CoreProperties;
import com.relaydesk.core.web.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Vendor service credential: every {@code /api/**} call must present the internal service
 * key. {@code /webhooks/sink} and {@code /api/health} are open by design.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private final CoreProperties properties;

    public InternalKeyFilter(CoreProperties properties) {
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
        String key = request.getHeader("X-Internal-Key");
        if (!properties.internalKey().equals(key)) {
            Problem.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "CORE_1005", "invalid service key", null);
            return;
        }
        chain.doFilter(request, response);
    }
}
