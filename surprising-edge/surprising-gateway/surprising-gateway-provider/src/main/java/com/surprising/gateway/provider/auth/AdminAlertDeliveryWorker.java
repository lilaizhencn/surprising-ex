package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertDeliveryWorkItem;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayProperties.DeliveryWorker;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdminAlertDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertDeliveryWorker.class);

    private final AdminAlertRepository alertRepository;
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AdminAlertDeliveryWorker(AdminAlertRepository alertRepository,
                                    GatewayProperties properties,
                                    RestTemplate restTemplate,
                                    ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            fixedDelayString = "${surprising.gateway.alerts.delivery-worker.poll-delay-ms:2000}",
            initialDelayString = "${surprising.gateway.alerts.delivery-worker.initial-delay-ms:5000}")
    public void scheduledDispatch() {
        try {
            runOnce(Instant.now());
        } catch (RuntimeException ex) {
            log.warn("admin alert delivery worker failed", ex);
        }
    }

    int runOnce(Instant now) {
        DeliveryWorker config = properties.getAlerts().getDeliveryWorker();
        if (!config.isEnabled()) {
            return 0;
        }
        List<AlertDeliveryWorkItem> deliveries = alertRepository.claimPendingDeliveries(
                now, config.getBatchSize(), config.getMaxAttempts(), config.getClaimLease());
        int processed = 0;
        for (AlertDeliveryWorkItem delivery : deliveries) {
            dispatch(delivery, config, now);
            processed++;
        }
        return processed;
    }

    private void dispatch(AlertDeliveryWorkItem delivery, DeliveryWorker config, Instant now) {
        if (!delivery.channelEnabled()) {
            alertRepository.markDeliverySkipped(delivery.alertDeliveryId(), "alert channel disabled", now);
            return;
        }
        String channelType = normalize(delivery.channelType());
        switch (channelType) {
            case "WEBHOOK", "SLACK", "PAGERDUTY" -> sendHttp(delivery, config, now);
            case "EMAIL" -> alertRepository.markDeliverySkipped(delivery.alertDeliveryId(),
                    "EMAIL alert channel requires an external mail adapter", now);
            case "OPS" -> alertRepository.markDeliverySkipped(delivery.alertDeliveryId(),
                    "OPS alert channel is in-app only", now);
            default -> alertRepository.markDeliverySkipped(delivery.alertDeliveryId(),
                    "unsupported alert channel type: " + delivery.channelType(), now);
        }
    }

    private void sendHttp(AlertDeliveryWorkItem delivery, DeliveryWorker config, Instant now) {
        try {
            URI endpoint = httpEndpoint(delivery.endpoint());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = objectMapper.writeValueAsString(payload(delivery, now));
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                alertRepository.markDeliverySent(delivery.alertDeliveryId(), now);
                return;
            }
            markFailed(delivery, config, "HTTP " + response.getStatusCode().value() + ": "
                    + truncate(response.getBody(), config), now);
        } catch (JacksonException | IllegalArgumentException | RestClientException ex) {
            markFailed(delivery, config, ex.getMessage(), now);
        }
    }

    private void markFailed(AlertDeliveryWorkItem delivery, DeliveryWorker config, String error, Instant now) {
        boolean exhausted = delivery.attemptCount() >= config.getMaxAttempts();
        Instant nextAttemptAt = exhausted ? null : now.plus(config.getRetryDelay());
        alertRepository.markDeliveryFailed(
                delivery.alertDeliveryId(),
                truncate(error == null || error.isBlank() ? "alert delivery failed" : error, config),
                exhausted,
                nextAttemptAt,
                now);
    }

    private URI httpEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required for HTTP alert channel");
        }
        URI uri = URI.create(endpoint.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https").contains(scheme)) {
            throw new IllegalArgumentException("HTTP alert endpoint must use http or https");
        }
        return uri;
    }

    private Map<String, Object> payload(AlertDeliveryWorkItem delivery, Instant now) {
        Map<String, Object> alert = alertPayload(delivery, now);
        String text = "[%s] %s %s %s".formatted(
                delivery.severity(), delivery.ruleCode(), delivery.domain(), delivery.message());
        return switch (normalize(delivery.channelType())) {
            case "SLACK" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("text", text);
                payload.put("alert", alert);
                yield payload;
            }
            case "PAGERDUTY" -> {
                Map<String, Object> pagerDutyPayload = new LinkedHashMap<>();
                pagerDutyPayload.put("summary", text);
                pagerDutyPayload.put("source", "surprising-gateway");
                pagerDutyPayload.put("severity", pagerDutySeverity(delivery.severity()));
                pagerDutyPayload.put("custom_details", alert);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event_action", "trigger");
                payload.put("dedup_key", delivery.ruleCode() + ":" + delivery.alertEventId());
                payload.put("payload", pagerDutyPayload);
                payload.put("alert", alert);
                yield payload;
            }
            default -> alert;
        };
    }

    private Map<String, Object> alertPayload(AlertDeliveryWorkItem delivery, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ADMIN_ALERT");
        payload.put("source", "surprising-gateway");
        payload.put("deliveryId", delivery.alertDeliveryId());
        payload.put("eventId", delivery.alertEventId());
        payload.put("channelId", delivery.alertChannelId());
        payload.put("channelCode", delivery.channelCode());
        payload.put("channelName", delivery.channelName());
        payload.put("channelType", delivery.channelType());
        payload.put("ruleCode", delivery.ruleCode());
        payload.put("domain", delivery.domain());
        payload.put("severity", delivery.severity());
        payload.put("eventStatus", delivery.eventStatus());
        payload.put("message", delivery.message());
        payload.put("attemptCount", delivery.attemptCount());
        payload.put("lastAttemptAt", now.toString());
        return payload;
    }

    private String pagerDutySeverity(String severity) {
        return switch (normalize(severity)) {
            case "CRITICAL" -> "critical";
            case "WARN" -> "warning";
            default -> "info";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, DeliveryWorker config) {
        if (value == null) {
            return "";
        }
        int limit = config.getMaxErrorMessageLength();
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
