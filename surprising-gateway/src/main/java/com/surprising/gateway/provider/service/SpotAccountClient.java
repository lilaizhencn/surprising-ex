package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

@Service
public class SpotAccountClient {

    private static final String INTERNAL_SERVICE = "surprising-gateway";
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public SpotAccountClient(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public void adjustBalance(long userId, String asset, long amountUnits,
                              String referenceId, String reason) {
        if (userId <= 0L || asset == null || asset.isBlank() || referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("spot balance adjustment request is invalid");
        }
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        if (wallet.getSpotAccountBaseUrl() == null || wallet.getSpotAccountBaseUrl().isBlank()) {
            throw new IllegalStateException("spot account endpoint is not configured");
        }
        if (wallet.getSpotAccountInternalSecret() == null || wallet.getSpotAccountInternalSecret().isBlank()) {
            throw new IllegalStateException("spot account internal authentication is not configured");
        }
        String normalizedAsset = asset.trim().toUpperCase(Locale.ROOT);
        String normalizedReason = reason == null ? "" : reason;
        long timestamp = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service", INTERNAL_SERVICE);
        headers.set("X-Internal-Timestamp", Long.toString(timestamp));
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "asset", normalizedAsset,
                "amountUnits", amountUnits,
                "referenceId", referenceId,
                "reason", normalizedReason);
        headers.set("X-Internal-Signature", signature(wallet.getSpotAccountInternalSecret(), timestamp,
                userId, normalizedAsset, amountUnits, referenceId, normalizedReason));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(trimTrailingSlash(wallet.getSpotAccountBaseUrl())
                            + "/api/v1/accounts/admin/balance-adjustments"),
                    HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                int status = response == null ? 0 : response.getStatusCode().value();
                if (status >= 400 && status < 500) {
                    throw new SpotAccountRejectedException("spot account adjustment returned HTTP " + status, status);
                }
                throw new SpotAccountUnknownException("spot account adjustment returned HTTP " + status, status);
            }
        } catch (RestClientException ex) {
            throw new SpotAccountUnknownException("spot account adjustment failed", 0, ex);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    String signature(String secret, long timestamp, long userId, String asset, long amountUnits,
                     String referenceId, String reason) {
        String canonical = canonical(timestamp, userId, asset, amountUnits, referenceId, reason);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("spot account internal signing failed", ex);
        }
    }

    static String canonical(long timestamp, long userId, String asset, long amountUnits,
                            String referenceId, String reason) {
        return INTERNAL_SERVICE + "\n" + timestamp + "\n" + userId + "\n"
                + asset + "\n" + amountUnits + "\n" + referenceId + "\n" + reason;
    }

    public static class SpotAccountRejectedException extends IllegalStateException {
        private final int status;

        public SpotAccountRejectedException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static class SpotAccountUnknownException extends IllegalStateException {
        private final int status;

        public SpotAccountUnknownException(String message, int status) {
            super(message);
            this.status = status;
        }

        public SpotAccountUnknownException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
