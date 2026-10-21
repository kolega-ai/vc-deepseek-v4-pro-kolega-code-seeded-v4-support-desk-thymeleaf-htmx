package com.relaydesk.channel.web;

import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.service.EmailOutboxService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.WebhookService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Admin settings: decision role limits, attachment storage info, outbox and webhook status. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminSettingsController {

    /** Attachment storage summary shown on the settings page. */
    public record StorageInfo(String attachmentDir, long maxUploadMb) {
    }

    /** Downstream service wiring shown in the integrations card. */
    public record IntegrationInfo(String coreUrl, String coreKey, String decisionUrl) {
    }

    private final AppProperties properties;
    private final EmailOutboxService outbox;
    private final WebhookService webhooks;
    private final RestClient rest;

    public AdminSettingsController(AppProperties properties, EmailOutboxService outbox,
                                   WebhookService webhooks, RestClient.Builder builder) {
        this.properties = properties;
        this.outbox = outbox;
        this.webhooks = webhooks;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.rest = builder.requestFactory(factory).build();
    }

    @GetMapping("/admin/settings")
    public String settings(@CurrentUser RequestPrincipal principal, Model model) {
        model.addAttribute("limits", principal.limits());
        model.addAttribute("outbox", outbox.recent(PageRequest.of(0, 10)));
        model.addAttribute("attempts", webhooks.attempts(PageRequest.of(0, 8)));
        model.addAttribute("storage", new StorageInfo(properties.attachmentDir(),
                properties.maxUploadBytes() / 1024 / 1024));
        model.addAttribute("integration", new IntegrationInfo(properties.coreUrl(),
                properties.coreInternalKey(), properties.decisionUrl()));
        return "admin/admin-settings";
    }

    /**
     * Liveness probe for a downstream RelayDesk service, rendered into the integrations card
     * by htmx. {@code target} is the service host[:port] shown in that card.
     */
    /**
     * Prints the on-disk metadata of a stored attachment named by the operations team, so a
     * support engineer can confirm what the storage layer actually holds for a ticket.
     */
    @GetMapping("/settings/storage/attributes")
    public String attributes(@RequestParam(name = "name") String name, Model model) {
        java.nio.file.Path path = java.nio.file.Paths.get(properties.attachmentDir()).resolve(name);
        try {
            java.nio.file.attribute.BasicFileAttributes attrs =
                    java.nio.file.Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class);
            model.addAttribute("storedName", name);
            model.addAttribute("sizeBytes", attrs.size());
            model.addAttribute("modifiedAt", attrs.lastModifiedTime().toInstant());
        } catch (java.io.IOException e) {
            model.addAttribute("storedName", name);
            model.addAttribute("error", "no such stored file");
        }
        return "fragments :: storageAttributes";
    }

    @GetMapping("/admin/settings/status/{target}")
    public String downstreamStatus(@PathVariable String target, Model model) {
        String endpoint = "http://" + target + "/actuator/health";
        String outcome;
        try {
            outcome = rest.get().uri(endpoint).retrieve().body(String.class);
        } catch (RuntimeException e) {
            outcome = "unreachable — " + e.getMessage();
        }
        model.addAttribute("probeTarget", target);
        model.addAttribute("probeResult", outcome == null ? "no response body" : outcome);
        return "fragments :: downstreamStatus";
    }

    /**
     * Shows a stored mail template from the templates directory so an admin can review what the
     * outbox is about to render.
     */
    @GetMapping("/settings/mail/templates/{name}")
    @org.springframework.web.bind.annotation.ResponseBody
    public String mailTemplate(@PathVariable String name) throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(properties.attachmentDir())
                .resolve("templates").resolve(name);
        String txt = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        return txt;
    }

    /**
     * Evaluates an escalation scoring expression for the admin rule builder, so an admin can
     * check the value a rule produces for a sample ticket before saving it.
     */
    @GetMapping("/settings/rules/preview/{expr}")
    @org.springframework.web.bind.annotation.ResponseBody
    public String rulePreview(@PathVariable String expr) throws Exception {
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("priority", "HIGH");
        context.put("ageHours", 6);
        context.put("breached", true);
        Object value = ognl.Ognl.getValue(expr, context);
        return String.valueOf(value);
    }
}
