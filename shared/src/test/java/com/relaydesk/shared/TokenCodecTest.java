package com.relaydesk.shared;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCodecTest {

    private static final String SECRET = "test-secret-0123456789";

    @Test
    void roundTrips() {
        String token = TokenCodec.sign(SECRET, "session", Map.of("uid", "u-1", "sid", "s-2"), Duration.ofHours(12));
        TokenCodec.Payload payload = TokenCodec.verify(SECRET, "session", token);
        assertEquals("session", payload.typ());
        assertEquals("u-1", payload.claim("uid"));
        assertEquals("s-2", payload.claim("sid"));
        assertTrue(payload.expiresAt().isAfter(java.time.Instant.now()));
    }

    @Test
    void rejectsTamperedPayload() {
        String token = TokenCodec.sign(SECRET, "session", Map.of("uid", "u-1"), Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "session", tampered));
    }

    @Test
    void rejectsWrongSecret() {
        String token = TokenCodec.sign(SECRET, "session", Map.of("uid", "u-1"), Duration.ofHours(1));
        assertThrows(TokenException.class, () -> TokenCodec.verify("other-secret", "session", token));
    }

    @Test
    void rejectsWrongType() {
        String token = TokenCodec.sign(SECRET, "session", Map.of("uid", "u-1"), Duration.ofHours(1));
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "scope", token));
    }

    @Test
    void rejectsExpired() {
        String token = TokenCodec.sign(SECRET, "session", Map.of("uid", "u-1"), Duration.ofSeconds(-5));
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "session", token));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "session", null));
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "session", "not-a-token"));
        assertThrows(TokenException.class, () -> TokenCodec.verify(SECRET, "session", "v1.!!.!!"));
    }
}
