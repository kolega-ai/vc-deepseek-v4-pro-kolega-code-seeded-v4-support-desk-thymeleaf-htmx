package com.relaydesk.core.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * External-system configuration of the vendor CRM. Both values are mandatory: an empty
 * {@code SCOPE_TOKEN_SECRET} or {@code CORE_INTERNAL_KEY} fails startup (@NotBlank),
 * mirroring the "fail fast on missing secret" contract of the other tiers.
 */
@Validated
@ConfigurationProperties(prefix = "core")
public record CoreProperties(
        @NotBlank String scopeTokenSecret,
        @NotBlank String internalKey) {
}
