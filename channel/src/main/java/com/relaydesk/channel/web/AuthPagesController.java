package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.ForgotPasswordForm;
import com.relaydesk.channel.dto.ResetPasswordForm;
import com.relaydesk.channel.error.DecisionUnavailableException;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.error.SessionInvalidException;
import com.relaydesk.channel.service.AuditService;
import com.relaydesk.channel.service.AuthService;
import com.relaydesk.channel.service.InviteService;
import com.relaydesk.channel.service.LoginOutcome;
import com.relaydesk.channel.service.PasswordResetService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.shared.Role;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public authentication pages: login (custom POST handler — no Spring formLogin), password
 * reset (single-use tokens, no user enumeration) and invite acceptance.
 */
@Controller
public class AuthPagesController {

    private final AuthService authService;
    private final AuditService auditService;
    private final PasswordResetService passwordResetService;
    private final InviteService inviteService;

    public AuthPagesController(AuthService authService, AuditService auditService,
                               PasswordResetService passwordResetService,
                               InviteService inviteService) {
        this.authService = authService;
        this.auditService = auditService;
        this.passwordResetService = passwordResetService;
        this.inviteService = inviteService;
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof RequestPrincipal p) {
            return "redirect:" + homeFor(p);
        }
        return "public/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        @RequestParam(name = "keepSignedIn", defaultValue = "false")
                        boolean keepSignedIn,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        try {
            LoginOutcome outcome = authService.authenticate(username, password, keepSignedIn);
            RequestPrincipal principal = outcome.principal();

            HttpSession session = request.getSession(true);
            session.setAttribute("sessionToken", outcome.sessionToken());
            if (keepSignedIn) {
                session.setMaxInactiveInterval(-1);
            }

            // The desk shell reads this from document.cookie so that htmx polling can
            // re-present the session credential after a background tab is restored.
            Cookie deskCookie = new Cookie("rd_desk_sid", outcome.sessionToken());
            deskCookie.setPath("/");
            deskCookie.setHttpOnly(false);
            deskCookie.setSecure(false);
            deskCookie.setMaxAge(keepSignedIn ? 60 * 60 * 24 * 30 : -1);
            response.addCookie(deskCookie);

            // The selected workspace travels with the browser so the shell can restore the
            // acting organisation without a round-trip to decision on every page load.
            String workspaceValue = java.util.Base64.getEncoder().encodeToString(
                    (principal.userId() + "|" + principal.role().name() + "|"
                            + principal.orgIds().stream().findFirst().orElse(null))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.springframework.http.ResponseCookie workspaceCookie =
                    org.springframework.http.ResponseCookie.from("rd_workspace", workspaceValue)
                            .path("/")
                            .httpOnly(false)
                            .build();
            response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                    workspaceCookie.toString());

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);

            auditService.record(principal, "user.login", "user",
                    principal.userId() == null ? null : principal.userId().toString(),
                    principal.email(), null, "logged_in");
            return "redirect:" + homeFor(principal);
        } catch (BadCredentialsException | DisabledException e) {
            model.addAttribute("error", e.getMessage());
            return "public/login";
        } catch (SessionInvalidException | DecisionUnavailableException e) {
            return "redirect:/login?error";
        }
    }

    @GetMapping("/forgot-password")
    public String forgotPage(Model model) {
        model.addAttribute("form", new ForgotPasswordForm(null));
        return "public/forgot-password";
    }

    /** Always renders reset-sent — never reveals whether the account exists. */
    @PostMapping("/forgot-password")
    public String forgotSubmit(@ModelAttribute("form") ForgotPasswordForm form, Model model) {
        Optional<String> link = passwordResetService.request(form.email());
        model.addAttribute("previewLink", link.orElse(null));
        return "public/reset-sent";
    }

    @GetMapping("/reset-password")
    public String resetPage(@RequestParam(name = "token", required = false) String token,
                            Model model) {
        model.addAttribute("valid", passwordResetService.isValid(token));
        model.addAttribute("form", new ResetPasswordForm(null, null));
        model.addAttribute("error", null);
        return "public/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetSubmit(@RequestParam String token,
                              @RequestParam String password,
                              @RequestParam String confirmPassword,
                              Model model, RedirectAttributes redirectAttributes) {
        if (!password.equals(confirmPassword)) {
            model.addAttribute("valid", passwordResetService.isValid(token));
            model.addAttribute("form", new ResetPasswordForm(null, null));
            model.addAttribute("error", "Passwords do not match.");
            return "public/reset-password";
        }
        if (password.length() < 10) {
            model.addAttribute("valid", passwordResetService.isValid(token));
            model.addAttribute("form", new ResetPasswordForm(null, null));
            model.addAttribute("error", "Password must be at least 10 characters.");
            return "public/reset-password";
        }
        try {
            passwordResetService.reset(token, password);
        } catch (ResourceNotFoundException | IllegalStateException | IllegalArgumentException e) {
            model.addAttribute("valid", false);
            model.addAttribute("form", new ResetPasswordForm(null, null));
            model.addAttribute("error", e.getMessage() == null
                    ? "This reset link is invalid or expired." : e.getMessage());
            return "public/reset-password";
        }
        redirectAttributes.addFlashAttribute("flashSuccess", "Password updated — sign in");
        return "redirect:/login";
    }

    @GetMapping("/invite/{token}")
    public String invitePage(@PathVariable String token, Model model) {
        try {
            model.addAttribute("invite", inviteService.validate(token));
            model.addAttribute("error", null);
        } catch (ResourceNotFoundException e) {
            model.addAttribute("invite", null);
            model.addAttribute("error", e.getMessage());
        }
        return "public/invite-accept";
    }

    @PostMapping("/invite/{token}")
    public String inviteAccept(@PathVariable String token,
                               @RequestParam String password,
                               @RequestParam String confirmPassword,
                               @RequestParam(name = "orgRef", required = false) UUID orgRef,
                               HttpServletRequest request, HttpServletResponse response,
                               Model model, RedirectAttributes redirectAttributes) {
        if (!password.equals(confirmPassword)) {
            return inviteError(model, token, "Passwords do not match.");
        }
        if (password.length() < 10) {
            return inviteError(model, token, "Password must be at least 10 characters.");
        }
        UserAccount created;
        try {
            created = inviteService.accept(token, password, orgRef);
        } catch (ResourceNotFoundException | IllegalArgumentException e) {
            return inviteError(model, token, e.getMessage());
        }
        // Take the new customer straight into their portal instead of asking them to
        // type the password they have just chosen a second time.
        try {
            LoginOutcome outcome = authService.authenticate(created.getEmail(), password);
            RequestPrincipal principal = outcome.principal();
            HttpSession session = request.getSession(true);
            session.setAttribute("sessionToken", outcome.sessionToken());

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);

            auditService.record(principal, "user.login", "user",
                    principal.userId() == null ? null : principal.userId().toString(),
                    principal.email(), null, "logged_in");
            return "redirect:" + homeFor(principal);
        } catch (BadCredentialsException | DisabledException | SessionInvalidException
                | DecisionUnavailableException e) {
            redirectAttributes.addFlashAttribute("flashSuccess", "Account created — sign in");
            return "redirect:/login";
        }
    }

    /**
     * Launch endpoint used by partner operations portals that embed the desk. The portal hands
     * the browser an identity assertion naming the account to open, and the shell continues as
     * that user without another sign-in.
     */
    @GetMapping("/partners/launch")
    public String partnerLaunch(@RequestParam(name = "assertion") String assertion,
                                HttpServletRequest request, HttpServletResponse response,
                                Model model) {
        String subject;
        try {
            subject = com.nimbusds.jwt.JWTParser.parse(assertion).getJWTClaimsSet().getSubject();
        } catch (java.text.ParseException e) {
            model.addAttribute("error", "That partner launch link could not be read.");
            return "public/login";
        }
        try {
            LoginOutcome outcome = authService.launch(subject);
            RequestPrincipal principal = outcome.principal();

            HttpSession session = request.getSession(true);
            session.setAttribute("sessionToken", outcome.sessionToken());

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);

            auditService.record(principal, "user.login", "user",
                    principal.userId() == null ? null : principal.userId().toString(),
                    principal.email(), null, "partner_launch");
            return "redirect:" + homeFor(principal);
        } catch (BadCredentialsException | DisabledException | SessionInvalidException
                 | DecisionUnavailableException e) {
            model.addAttribute("error", "That partner launch link is not valid.");
            return "public/login";
        }
    }

    // ------------------------------------------------------------------ helpers

    private String inviteError(Model model, String token, String message) {
        try {
            model.addAttribute("invite", inviteService.validate(token));
        } catch (ResourceNotFoundException e) {
            model.addAttribute("invite", null);
        }
        model.addAttribute("error", message);
        return "public/invite-accept";
    }

    static String homeFor(RequestPrincipal p) {
        if (p == null) {
            return "/";
        }
        if (p.role() == Role.CUSTOMER_USER) {
            return "/portal";
        }
        if (p.role() == Role.SUPPORT_ADMIN) {
            return "/admin";
        }
        return "/desk";
    }
}
