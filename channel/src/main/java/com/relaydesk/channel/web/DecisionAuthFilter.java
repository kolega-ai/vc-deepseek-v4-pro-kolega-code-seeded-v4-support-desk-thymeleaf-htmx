package com.relaydesk.channel.web;

import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.client.DecisionPrincipal;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.error.DecisionUnavailableException;
import com.relaydesk.channel.error.SessionInvalidException;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.AuthService;
import com.relaydesk.channel.service.RequestPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The heart of the trust model: decision authorizes every request. For every request outside
 * the public set this filter resolves a session token (from the session, or — for the API
 * surface — minted for the Bearer key's owner), calls {@code DecisionClient.validate(token)}
 * with no cross-request caching, and materializes a {@link RequestPrincipal} both as the
 * {@code requestPrincipal} request attribute and as the SecurityContext authentication, so
 * {@code @PreAuthorize}/{@code sec:authorize} reflect decision's database immediately.
 *
 * <p>Channel fails closed: if decision is unreachable the request is rejected with 503.
 */
@Component
public class DecisionAuthFilter extends OncePerRequestFilter {

    private static final String REQUEST_PRINCIPAL_ATTR = "requestPrincipal";
    private static final String API_KEY_OWNER_ATTR = "apiKeyOwner";
    private static final String SESSION_TOKEN_ATTR = "sessionToken";
    private static final String DESK_COOKIE = "rd_desk_sid";
    private static final String WORKSPACE_COOKIE = "rd_workspace";
    private static final String API_PREFIX = "/api/v1/";

    private final DecisionClient decision;
    private final AuthService authService;
    private final UserAccountRepository users;

    public DecisionAuthFilter(DecisionClient decision, AuthService authService,
                              UserAccountRepository users) {
        this.decision = decision;
        this.authService = authService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = WebPaths.path(request);
        // 1. Public surface: nothing to resolve.
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean apiRequest = path.startsWith(API_PREFIX);

        // 2. Resolve the session token.
        String token = null;
        if (request.getAttribute(API_KEY_OWNER_ATTR) instanceof ApiKeyContext ctx) {
            UserAccount owner = users.findById(ctx.ownerId()).orElse(null);
            if (owner == null || !owner.isEnabled()) {
                if (apiRequest) {
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "invalid_api_key", "API key owner missing or disabled");
                } else {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                }
                return;
            }
            token = authService.sessionTokenFor(owner);
        } else {
            HttpSession session = request.getSession(false);
            Object attr = session == null ? null : session.getAttribute(SESSION_TOKEN_ATTR);
            if (attr == null) {
                // A restored background tab can lose the container session before the
                // credential itself expires; the desk cookie carries it in that case.
                attr = deskCookieValue(request);
            }
            if (attr == null) {
                // No session token: Spring Security will handle unauthenticated protected paths.
                chain.doFilter(request, response);
                return;
            }
            token = (String) attr;
        }

        // 3. Decision authorizes every request.
        DecisionPrincipal d;
        try {
            d = decision.validate(token);
        } catch (SessionInvalidException e) {
            handleInvalid(request, response, apiRequest, e);
            return;
        } catch (DecisionUnavailableException e) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        // 4. Materialize the principal into the request and the SecurityContext.
        try {
            RequestPrincipal principal = applyWorkspace(request,
                    RequestPrincipal.fromDecision(d, token));
            request.setAttribute(REQUEST_PRINCIPAL_ATTR, principal);
            SecurityContext context = SecurityContextHolder.getContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + d.role()))));
        } catch (SessionInvalidException e) {
            handleInvalid(request, response, apiRequest, e);
            return;
        }
        chain.doFilter(request, response);
    }

    private void handleInvalid(HttpServletRequest request, HttpServletResponse response,
                               boolean apiRequest, SessionInvalidException cause)
            throws IOException {
        if (apiRequest) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_session", cause.getMessage());
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect("/login?expired");
    }

    private void writeJsonError(HttpServletResponse response, int status, String error,
                                String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\""
                + (message == null ? "" : message.replace("\"", "'")) + "\"}");
    }

    private String deskCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (DESK_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * Applies the browser's selected workspace organisation to a customer principal so the
     * portal and API surface resolve the requested org from the cookie the shell maintains.
     */
    private RequestPrincipal applyWorkspace(HttpServletRequest request, RequestPrincipal principal) {
        String value = cookieValue(request, WORKSPACE_COOKIE);
        if (value == null || principal.isStaff()) {
            return principal;
        }
        try {
            String[] parts = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
                    .split("\\|");
            if (parts.length > 2 && !parts[2].isBlank() && !"null".equals(parts[2])) {
                return principal.withOrgScope(Set.of(UUID.fromString(parts[2])));
            }
        } catch (RuntimeException ignored) {
            // keep the decision-issued organisation scope
        }
        return principal;
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean isPublicPath(String path) {
        if (path.equals("/") || path.equals("/login") || path.equals("/forgot-password")
                || path.equals("/reset-password") || path.equals("/favicon.ico")
                || path.equals("/actuator/health")) {
            return true;
        }
        return path.startsWith("/invite/") || path.equals("/partners/launch")
                || path.startsWith("/kb/") || path.equals("/kb")
                || path.startsWith("/error") || path.startsWith("/css/")
                || path.startsWith("/js/") || path.startsWith("/fonts/")
                || path.startsWith("/vendor/");
    }
}
