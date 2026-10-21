package com.relaydesk.channel.error;

/** Thrown when the decision tier (authorization authority) cannot be reached or fails. */
public class DecisionUnavailableException extends RuntimeException {

    public DecisionUnavailableException(String message) {
        super(message);
    }

    public DecisionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
