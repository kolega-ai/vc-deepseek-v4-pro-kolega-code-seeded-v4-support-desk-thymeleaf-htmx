package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.service.AuditService;
import com.relaydesk.channel.service.AuthService;
import com.relaydesk.channel.service.InviteService;
import com.relaydesk.channel.service.LoginOutcome;
import com.relaydesk.channel.service.PasswordResetService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Login POST: good credentials redirect by role; bad credentials re-render the sign-in page. */
class LoginControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
    private final InviteService inviteService = mock(InviteService.class);

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthPagesController(
                        authService, auditService, passwordResetService, inviteService))
                .build();
    }

    private static RequestPrincipal principal(Role role) {
        return new RequestPrincipal(UUID.randomUUID(), "jordan.lee@acme.test", "Jordan Lee",
                role, Set.of(UUID.randomUUID()), Set.of(), Map.of(), "sid", "token");
    }

    @Test
    void customerLoginRedirectsToPortal() throws Exception {
        UserAccount user = new UserAccount();
        user.setEmail("jordan.lee@acme.test");
        when(authService.authenticate("jordan.lee@acme.test", "Customer@Relay1", false))
                .thenReturn(new LoginOutcome(user, "token", principal(Role.CUSTOMER_USER)));

        mvc.perform(post("/login")
                        .param("username", "jordan.lee@acme.test")
                        .param("password", "Customer@Relay1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/portal"));
        verify(auditService).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("user.login"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void badCredentialsRerendersSignIn() throws Exception {
        when(authService.authenticate("jordan.lee@acme.test", "wrong", false))
                .thenThrow(new BadCredentialsException("That password does not match."));

        mvc.perform(post("/login")
                        .param("username", "jordan.lee@acme.test")
                        .param("password", "wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/login"));
    }
}
