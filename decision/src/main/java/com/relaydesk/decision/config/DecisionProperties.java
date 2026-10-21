package com.relaydesk.decision.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Decision tier configuration. All three secrets are mandatory: blank values make the
 * application fail fast at startup (validation runs at configuration binding time).
 */
@Validated
@ConfigurationProperties(prefix = "decision")
public record DecisionProperties(
        @NotBlank String sessionTokenSecret,
        @NotBlank String scopeTokenSecret,
        @NotBlank String channelInternalKey) {
}
