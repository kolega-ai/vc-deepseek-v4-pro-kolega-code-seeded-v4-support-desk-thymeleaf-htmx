package com.relaydesk.decision.web;

import com.relaydesk.decision.dto.PrincipalResponse;
import com.relaydesk.decision.dto.ScopeTokenResponse;
import com.relaydesk.decision.dto.TokenRequest;
import com.relaydesk.decision.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token endpoints. Only the signed token is read from the request; no user id, role, scope,
 * or limit fields are accepted from headers, paths, queries, or body fields.
 */
@RestController
@RequestMapping("/internal/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/validate")
    public PrincipalResponse validate(@RequestBody TokenRequest request) {
        return authService.validate(request.token());
    }

    @PostMapping("/scope-token")
    public ScopeTokenResponse scopeToken(@RequestBody TokenRequest request) {
        return authService.mintScopeToken(request.token());
    }
}
