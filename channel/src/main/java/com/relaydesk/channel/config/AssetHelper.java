package com.relaydesk.channel.config;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Static asset versioning helper used by the Thymeleaf layout to append cache-busting
 * suffixes to CSS/JS references.
 *
 * <p><b>Precision baseline #2:</b> {@code java.util.Random} is used here ONLY to produce a
 * cache-busting suffix for static assets. This is a non-cryptographic use: a cache-buster
 * needs no unpredictability, so a plain {@link Random} is the correct, deliberate choice
 * (kept as {@code java.util.Random}, not {@code SecureRandom}).
 */
@Component
public class AssetHelper {

    private final String suffix;

    public AssetHelper() {
        this.suffix = "v=" + new Random().nextInt(1_000_000_000);
    }

    /** Appends the (process-stable) cache-busting suffix to a static asset path. */
    public String bust(String path) {
        return path + (path.contains("?") ? "&" : "?") + suffix;
    }
}
