package com.relaydesk.channel.error;

/**
 * Error returned by the external customer-core (core-mock), or a transport failure talking
 * to it. {@code coreCode} mirrors the vendor's error code (e.g. CORE_1001).
 */
public class CoreClientException extends RuntimeException {

    private final String coreCode;
    private final int status;

    public CoreClientException(String coreCode, String message, int status) {
        super(message);
        this.coreCode = coreCode;
        this.status = status;
    }

    public String getCoreCode() {
        return coreCode;
    }

    public int getStatus() {
        return status;
    }
}
