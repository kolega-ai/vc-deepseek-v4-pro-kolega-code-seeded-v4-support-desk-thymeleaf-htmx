package com.relaydesk.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact opaque signed-token codec shared by the three tiers.
 *
 * <p>Wire format: {@code v1.<base64url(json)>.<base64url(HMAC-SHA256(json))>} where the JSON
 * payload is {@code {"typ": "...", "claims": {k: v, ...}, "exp": <epoch seconds>}}.
 *
 * <p>Tokens are verified (HMAC + expiry + type) before any claim is read, so tampered payloads
 * never reach downstream code.
 *
 * <ul>
 *   <li>{@code session} tokens are minted by {@code channel} (typ "session", claims
 *       {@code uid}, {@code sid}) and verified by {@code decision}.</li>
 *   <li>{@code scope} tokens are minted by {@code decision} (typ "scope", claims
 *       {@code uid}, {@code role}, {@code orgs}, {@code teams}, {@code limits}) and verified
 *       and enforced by {@code core-mock}.</li>
 * </ul>
 */
public final class TokenCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final String HMAC_ALGO = "HmacSHA256";

    private TokenCodec() {
    }

    /** Mints a signed token of the given type carrying the given string claims, valid for {@code ttl}. */
    public static String sign(String secret, String typ, Map<String, String> claims, Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typ", typ);
        payload.put("claims", claims);
        payload.put("exp", exp);
        try {
            String json = MAPPER.writeValueAsString(payload);
            String body = B64_ENC.encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return "v1." + body + "." + hmac(secret, body);
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenException("cannot serialize token", e);
        }
    }

    /**
     * Verifies a token: structure, HMAC signature (constant-time compare), type and expiry.
     *
     * @throws TokenException if the token is missing, malformed, tampered with, of another type, or expired
     */
    public static Payload verify(String secret, String expectedTyp, String token) {
        if (token == null || token.isBlank()) {
            throw new TokenException("missing token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new TokenException("malformed token");
        }
        String expectedSig = hmac(secret, parts[1]);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new TokenException("bad signature");
        }
        try {
            String json = new String(B64_DEC.decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode node = MAPPER.readTree(json);
            String typ = node.path("typ").asText("");
            if (!expectedTyp.equals(typ)) {
                throw new TokenException("unexpected token type");
            }
            long exp = node.path("exp").asLong(-1);
            if (exp <= Instant.now().getEpochSecond()) {
                throw new TokenException("token expired");
            }
            Map<String, String> claims = MAPPER.convertValue(
                    node.path("claims"), new TypeReference<Map<String, String>>() {
                    });
            return new Payload(typ, claims, Instant.ofEpochSecond(exp));
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenException("unparseable token", e);
        }
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return B64_ENC.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new TokenException("cannot compute signature", e);
        }
    }

    /** A verified token: its type, its string claims, and its expiry. */
    public record Payload(String typ, Map<String, String> claims, Instant expiresAt) {
        public String claim(String key) {
            return claims.get(key);
        }
    }
}
