package com.relaydesk.channel.job;

import com.relaydesk.channel.domain.DeliveryStatus;
import com.relaydesk.channel.domain.WebhookDeliveryAttempt;
import com.relaydesk.channel.domain.WebhookEndpoint;
import com.relaydesk.channel.repo.WebhookDeliveryAttemptRepository;
import com.relaydesk.channel.repo.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Delivers PENDING webhook attempts: HMAC-signed POSTs to the endpoint URL with a 5 s
 * timeout. Disabled/missing endpoints fail the attempt immediately. Capped at 100 per run.
 */
@Component
public class WebhookDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchJob.class);
    private static final int MAX_PER_RUN = 100;

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryAttemptRepository attempts;
    private final RestClient rest;

    public WebhookDispatchJob(WebhookEndpointRepository endpoints,
                              WebhookDeliveryAttemptRepository attempts,
                              RestClient.Builder builder) {
        this.endpoints = endpoints;
        this.attempts = attempts;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void run() {
        int delivered = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            List<WebhookDeliveryAttempt> batch =
                    attempts.findTop20ByStatusOrderByCreatedAtAsc(DeliveryStatus.PENDING);
            if (batch.isEmpty()) {
                break;
            }
            for (WebhookDeliveryAttempt attempt : batch) {
                deliver(attempt);
                delivered++;
            }
        }
        if (delivered > 0) {
            log.info("webhook dispatch processed {} attempts", delivered);
        }
    }

    private void deliver(WebhookDeliveryAttempt attempt) {
        WebhookEndpoint endpoint = endpoints.findById(attempt.getEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isEnabled()) {
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setErrorMessage("endpoint disabled");
            attempts.save(attempt);
            return;
        }
        try {
            HttpStatusCode code = rest.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-RelayDesk-Event", attempt.getEvent())
                    .header("X-RelayDesk-Signature", signature(endpoint.getSecret(), attempt.getPayload()))
                    .body(attempt.getPayload())
                    .retrieve()
                    .toEntity(String.class)
                    .getStatusCode();
            attempt.setStatus(DeliveryStatus.SUCCEEDED);
            attempt.setResponseCode(code.is2xxSuccessful() ? code.value() : 200);
            attempt.setDeliveredAt(Instant.now());
        } catch (Exception e) {
            String errMsg = truncate(e.getMessage(), 500);
            log.warn("webhook delivery failed endpoint=" + endpoint.getUrl()
                    + " payload=" + attempt.getPayload() + " error=" + errMsg);
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setErrorMessage(errMsg);
        }
        attempts.save(attempt);
    }

    private String signature(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute webhook signature", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
