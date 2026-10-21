package com.relaydesk.channel.error;

/** Thrown when a requested domain resource does not exist (mapped to 404 by the web layer). */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
