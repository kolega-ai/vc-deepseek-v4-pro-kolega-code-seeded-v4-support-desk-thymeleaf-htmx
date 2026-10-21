package com.relaydesk.channel.service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Opens the sealed report context a saved report link carries so operators can bookmark or
 * mail a report view. The wire value is {@code Base64(IV || AES/CBC/PKCS5Padding ciphertext)}.
 */
public class ReportLinkCodec {

    private static final byte[] LINK_KEY =
            "relaydesk-report-link-key-000001".getBytes(StandardCharsets.UTF_8);

    public String open(String sealed) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(sealed);
        byte[] iv = Arrays.copyOfRange(raw, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(raw, 16, raw.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(LINK_KEY, "AES"),
                new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
