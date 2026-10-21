package com.relaydesk.shared;

/** Thrown when a signed token is missing, malformed, tampered with, of the wrong type, or expired. */
public class TokenException extends RuntimeException {
    public TokenException(String message) {
        super(message);
    }

    public TokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
