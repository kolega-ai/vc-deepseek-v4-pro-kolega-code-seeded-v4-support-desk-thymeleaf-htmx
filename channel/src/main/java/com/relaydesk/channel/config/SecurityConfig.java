package com.relaydesk.channel.config;

import com.relaydesk.channel.web.ApiKeyAuthFilter;
import com.relaydesk.channel.web.DecisionAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Channel security: session-based CSRF, no Spring formLogin (POST /login is handled by a
 * custom controller), session-fixation protection, and the two trust filters:
 * {@link ApiKeyAuthFilter} (stateless Bearer auth for /api/v1/**) and
 * {@link DecisionAuthFilter} (validates every authenticated request against decision).
 *
 * <p>No {@code DaoAuthenticationProvider} is needed: password checking happens in
 * {@code AuthService.authenticate} against the channel user table.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   DecisionAuthFilter decisionAuthFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf
                        // Session-based token; htmx sends the raw token from the meta tag and
                        // Thymeleaf injects hidden inputs on th:action forms (both wired in app.js).
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // The JSON API authenticates via a Bearer key (no cookies) — CSRF is not
                        // applicable there and would break curl/scripted clients.
                        .ignoringRequestMatchers("/api/v1/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/forgot-password", "/reset-password",
                                "/invite/**", "/partners/launch", "/kb", "/kb/**", "/error",
                                "/css/**", "/js/**", "/fonts/**", "/vendor/**",
                                "/favicon.ico", "/actuator/**", "/api/v1/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessUrl("/login?logout"))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .invalidSessionUrl("/login?expired"))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(decisionAuthFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
