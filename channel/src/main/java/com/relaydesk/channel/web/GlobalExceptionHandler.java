package com.relaydesk.channel.web;

import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.error.DecisionUnavailableException;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.service.RequestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Global error mapping: friendly 403/404/503/500 pages, flash messages for user-correctable
 * errors (redirected back to the originating page), and plain-text/JSON handling for htmx and
 * API requests. No stack traces are ever rendered.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public Object accessDenied(AccessDeniedException e, HttpServletRequest request) {
        if (isApi(request)) {
            return json(HttpStatus.FORBIDDEN, "forbidden", "insufficient permissions");
        }
        if (isHtmx(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
        }
        return view("error/403", HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Object notFound(ResourceNotFoundException e, HttpServletRequest request) {
        if (isApi(request)) {
            return json(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
        }
        return view("error/404", HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(DecisionUnavailableException.class)
    public Object decisionUnavailable(DecisionUnavailableException e, HttpServletRequest request) {
        if (isApi(request)) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "decision_unavailable", "authorization service unreachable");
        }
        return view("error/503", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(CoreClientException.class)
    public Object coreError(CoreClientException e, HttpServletRequest request,
                            RedirectAttributes redirectAttributes) {
        String message = "Customer core error " + e.getCoreCode() + ": " + e.getMessage();
        if (request.getRequestURI().startsWith("/admin/orgs")) {
            redirectAttributes.addFlashAttribute("flashError", message);
            return "redirect:/admin/orgs";
        }
        redirectAttributes.addFlashAttribute("flashError", message);
        return "redirect:" + fallback(request, "/");
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public Object badRequest(RuntimeException e, HttpServletRequest request,
                             RedirectAttributes redirectAttributes) {
        // IllegalTransitionException extends IllegalArgumentException — same handling.
        if (isApi(request)) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", e.getMessage());
        }
        redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        return "redirect:" + fallback(request, roleHome());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Object validation(BindException e, HttpServletRequest request,
                             RedirectAttributes redirectAttributes) {
        if (isApi(request)) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    firstError(e) == null ? "validation failed" : firstError(e));
        }
        redirectAttributes.addFlashAttribute("flashError",
                firstError(e) == null ? "Please check the form and try again." : firstError(e));
        return "redirect:" + fallback(request, roleHome());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object uploadTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        if (isApi(request)) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, "file_too_large", "File too large (max 10 MB)");
        }
        redirectAttributes.addFlashAttribute("flashError", "File too large (max 10 MB)");
        return "redirect:" + fallback(request, roleHome());
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public Object badCredentials(RuntimeException e, HttpServletRequest request) {
        if (isApi(request)) {
            return json(HttpStatus.UNAUTHORIZED, "invalid_session", "session invalid");
        }
        return "redirect:/login";
    }

    @ExceptionHandler(ApiV1Controller.ApiAuthRequiredException.class)
    public Object apiAuthRequired(ApiV1Controller.ApiAuthRequiredException e) {
        return json(HttpStatus.UNAUTHORIZED, "invalid_api_key", "Missing or invalid API key");
    }

    @ExceptionHandler(Exception.class)
    public Object generic(Exception e, HttpServletRequest request) {
        log.error("Unhandled error for {} {}", request.getMethod(), request.getRequestURI(), e);
        if (isApi(request)) {
            return json(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal error");
        }
        return view("error", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ helpers

    private boolean isApi(HttpServletRequest request) {
        return WebPaths.path(request).startsWith("/api/v1/");
    }

    private boolean isHtmx(HttpServletRequest request) {
        return request.getHeader("HX-Request") != null;
    }

    private ModelAndView view(String name, HttpStatus status) {
        ModelAndView mav = new ModelAndView(name);
        mav.setStatus(status);
        return mav;
    }

    private String firstError(BindException e) {
        FieldError error = e.getBindingResult().getFieldError();
        return error == null ? null : error.getField() + ": " + error.getDefaultMessage();
    }

    private String fallback(HttpServletRequest request, String defaultTarget) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank() || referer.equals(request.getRequestURL().toString())) {
            return defaultTarget;
        }
        String path = referer.replaceFirst("^https?://[^/]*", "");
        return path.isEmpty() ? defaultTarget : path;
    }

    private String roleHome() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof RequestPrincipal p) {
            if (!p.isStaff()) {
                return "/portal";
            }
            return p.isAdmin() ? "/admin" : "/desk";
        }
        return "/";
    }

    private ResponseEntity<String> json(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(
                "{\"error\":\"" + error + "\",\"message\":\""
                        + (message == null ? "" : message.replace("\"", "'")) + "\"}");
    }
}
