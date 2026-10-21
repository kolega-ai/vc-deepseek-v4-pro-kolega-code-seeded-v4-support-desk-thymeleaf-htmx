package com.relaydesk.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Decision tier: the authorization authority. It resolves opaque signed session tokens and
 * loads the acting user, role, scope and limits from its own database on every request.
 * Identity fields supplied in headers, paths, queries or request bodies are ignored by
 * construction — only the signed token and the service key are read.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DecisionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DecisionApplication.class, args);
    }
}
