package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWalletWebhookRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CustodyWalletWebhookService {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300L;
    private static final String DEPOSIT_CONFIRMED = "DEPOSIT.CONFIRMED";
    private static final String DEPOSIT_REORGED = "DEPOSIT.REORGED";

    private final GatewayProperties properties;
    private final CustodyWalletWebhookRepository repository;
    private final CustodyWalletClient walletClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CustodyWalletWebhookService(GatewayProperties properties,
                                       CustodyWalletWebhookRepository repository,
                                       CustodyWalletClient walletClient,
                                       RestTemplate restTemplate,
                                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.repository = repository;
        this.walletClient = walletClient;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public void handle(String eventId, String eventType, String timestamp,
                       String signature, byte[] body) {
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        if (!wallet.isEnabled() || wallet.getWebhookSecret() == null || wallet.getWebhookSecret().isBlank()) {
            throw new IllegalStateException("custody wallet webhook is not configured");
        }
        if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()
                || timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("wallet webhook headers are required");
        }
        byte[] rawBody = body == null ? new byte[0] : body;
        long eventTimestamp = parseTimestamp(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - eventTimestamp) > MAX_CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("wallet webhook timestamp is outside the allowed window");
        }
        verifySignature(wallet.getWebhookSecret(), eventTimestamp, signature, rawBody);
        Map<String, Object> event = readEvent(rawBody);
        String normalizedType = eventType.trim().toUpperCase(Locale.ROOT);
        Object payloadType = event.get("type");
        if (payloadType != null && !normalizedType.equals(String.valueOf(payloadType).toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("wallet webhook event type does not match its payload");
        }
        CustodyWalletWebhookRepository.ClaimResult claim = repository.claim(
                eventId, normalizedType, sha256(rawBody), Instant.now());
        if (claim == CustodyWalletWebhookRepository.ClaimResult.PROCESSED) {
            return;
        }
        if (claim == CustodyWalletWebhookRepository.ClaimResult.IN_PROGRESS) {
            throw new IllegalStateException("wallet webhook event is already being processed");
        }
        try {
            if (DEPOSIT_CONFIRMED.equals(normalizedType) || DEPOSIT_REORGED.equals(normalizedType)) {
                postSpotAdjustment(eventId, normalizedType, event);
            }
            repository.markProcessed(eventId, Instant.now());
        } catch (RuntimeException ex) {
            repository.markFailed(eventId, ex.getMessage(), Instant.now());
            throw ex;
        }
    }

    String signature(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] message = (timestamp + "." + new String(body, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            return "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message));
        } catch (Exception ex) {
            throw new IllegalStateException("wallet webhook signing failed", ex);
        }
    }

    private void postSpotAdjustment(String eventId, String eventType, Map<String, Object> event) {
        Map<String, Object> data = mapValue(event.get("data"), "wallet webhook data");
        long userId = userId(stringValue(data.get("subject"), "wallet webhook subject"));
        String asset = stringValue(data.get("asset"), "wallet webhook asset").toUpperCase(Locale.ROOT);
        String amount = stringValue(data.get(DEPOSIT_REORGED.equals(eventType) ? "reversedAmount" : "availableAmount"), "");
        if (amount.isBlank()) {
            amount = stringValue(data.get("amount"), "wallet webhook amount");
        }
        long units = walletClient.amountUnits(asset, amount);
        if (DEPOSIT_REORGED.equals(eventType)) {
            units = Math.negateExact(units);
        }
        String referenceId = "custody-wallet:" + eventId + ":" + eventType.toLowerCase(Locale.ROOT);
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "asset", asset,
                "amountUnits", units,
                "referenceId", referenceId,
                "reason", "custody wallet " + eventType);
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        String baseUrl = wallet.getSpotAccountBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("spot account endpoint is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Source", "custody-wallet-webhook");
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(trimTrailingSlash(baseUrl) + "/api/v1/accounts/admin/balance-adjustments"),
                    HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("spot account adjustment returned " + response.getStatusCode().value());
            }
        } catch (RestClientException ex) {
            throw new IllegalStateException("spot account adjustment failed", ex);
        }
    }

    private Map<String, Object> readEvent(byte[] body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("wallet webhook body is invalid JSON", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return (Map<String, Object>) map;
    }

    private String stringValue(Object value, String field) {
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.isBlank() && !field.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return result;
    }

    private long userId(String subject) {
        if (!subject.startsWith("user:")) {
            throw new IllegalArgumentException("wallet webhook subject is invalid");
        }
        try {
            long userId = Long.parseLong(subject.substring("user:".length()));
            if (userId <= 0L) throw new NumberFormatException();
            return userId;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("wallet webhook subject is invalid", ex);
        }
    }

    private long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("wallet webhook timestamp is invalid", ex);
        }
    }

    private void verifySignature(String secret, long timestamp, String signature, byte[] body) {
        String expected = signature(secret, timestamp, body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("wallet webhook signature is invalid");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
