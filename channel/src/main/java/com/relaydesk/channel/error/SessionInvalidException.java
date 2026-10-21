package com.relaydesk.channel.error;

/**
 * Thrown when decision rejects a session token: unknown user, disabled account, expired/tampered
 * token, or an unknown role value in the decision response. The web layer logs the user out.
 */
public class SessionInvalidException extends RuntimeException {

    public SessionInvalidException(String message) {
        super(message);
    }
}
