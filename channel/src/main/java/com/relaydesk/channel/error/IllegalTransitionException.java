package com.relaydesk.channel.error;

/**
 * Thrown when a state-machine transition is not in the allowed edge list.
 * Extends {@link IllegalArgumentException} so callers relying on the argument contract keep working.
 */
public class IllegalTransitionException extends IllegalArgumentException {

    public IllegalTransitionException(String message) {
        super(message);
    }
}
