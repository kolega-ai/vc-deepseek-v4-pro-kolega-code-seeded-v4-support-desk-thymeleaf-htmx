package com.relaydesk.decision.web;

import com.relaydesk.decision.config.DecisionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Service-credential check for /internal/ endpoints. Decision ignores any identity claims
 * from headers, query params, or bodies — the key only proves the caller is the channel tier,
 * never who the end user is.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "X-Internal-Key";

    private final DecisionProperties properties;

    public InternalKeyFilter(DecisionProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/internal/") || "/internal/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(KEY_HEADER);
        if (properties.channelInternalKey().equals(key)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"invalid_service_key\",\"message\":\"missing or invalid service key\"}");
    }
}
