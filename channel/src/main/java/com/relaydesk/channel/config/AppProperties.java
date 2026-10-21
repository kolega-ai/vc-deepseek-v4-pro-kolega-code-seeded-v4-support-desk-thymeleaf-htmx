package com.relaydesk.channel.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Channel configuration. The token secret and internal service keys are required at runtime
 * (development defaults are injected by docker-compose only) — the application fails fast on
 * startup when they are missing so a misconfigured authorization path cannot silently run.
 */
@Validated
@ConfigurationProperties(prefix = "relaydesk")
public record AppProperties(
        @NotBlank String sessionTokenSecret,
        @NotBlank String decisionUrl,
        @NotBlank String decisionInternalKey,
        @NotBlank String coreUrl,
        @NotBlank String coreInternalKey,
        String attachmentDir,
        boolean seedEnabled,
        long maxUploadBytes) {

    public AppProperties {
        if (attachmentDir == null || attachmentDir.isBlank()) {
            attachmentDir = "./attachments";
        }
        if (maxUploadBytes <= 0) {
            maxUploadBytes = 10L * 1024 * 1024;
        }
    }
}
