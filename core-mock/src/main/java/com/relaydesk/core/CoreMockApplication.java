package com.relaydesk.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Vendor-core stub for the fictional external CRM "Lumina Customer Core" (port 8082). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreMockApplication.class, args);
    }
}
