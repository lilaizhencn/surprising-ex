package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Source", "custody-wallet-withdrawal");
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "asset", asset.trim().toUpperCase(Locale.ROOT),
                "amountUnits", amountUnits,
                "referenceId", referenceId,
                "reason", reason == null ? "" : reason);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(trimTrailingSlash(wallet.getSpotAccountBaseUrl())
                            + "/api/v1/accounts/admin/balance-adjustments"),
                    HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                int status = response == null ? 0 : response.getStatusCode().value();
                throw new IllegalStateException("spot account adjustment returned HTTP " + status);
            }
        } catch (RestClientException ex) {
            throw new IllegalStateException("spot account adjustment failed", ex);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
