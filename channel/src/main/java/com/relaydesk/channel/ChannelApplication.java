package com.relaydesk.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// No UserDetailsService-based login is used: channel authenticates against its own user
// table via AuthService and consults decision for authorization on every request.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class ChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}
