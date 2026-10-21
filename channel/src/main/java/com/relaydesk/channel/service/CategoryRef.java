package com.relaydesk.channel.service;

import java.util.UUID;

/** Lightweight reference-data category entry for the desk-side reference endpoint. */
public record CategoryRef(UUID id, String name) {
}
