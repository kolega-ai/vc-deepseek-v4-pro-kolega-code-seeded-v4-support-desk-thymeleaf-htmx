package com.relaydesk.channel.service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Opens the sealed envelope the legacy mail-gateway partners wrap their ticket documents in.
 * The wire value is {@code Base64(IV || AES/CBC/PKCS5Padding ciphertext)} under the shared
 * relay key configured for the gateway integration.
 */
public class PartnerEnvelopeCodec {

    private static final byte[] RELAY_KEY =
            "relaydesk-envelope-key-2024-0001".getBytes(StandardCharsets.UTF_8);

    public String open(String sealed) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(sealed);
        byte[] iv = Arrays.copyOfRange(raw, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(raw, 16, raw.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(RELAY_KEY, "AES"),
                new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
