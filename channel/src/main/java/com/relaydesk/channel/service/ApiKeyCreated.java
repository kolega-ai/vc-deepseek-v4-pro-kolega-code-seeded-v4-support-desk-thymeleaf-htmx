package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.ApiKey;

/** Result of creating an API key: the entity plus the plaintext (shown exactly once). */
public record ApiKeyCreated(ApiKey key, String plaintext) {
}
