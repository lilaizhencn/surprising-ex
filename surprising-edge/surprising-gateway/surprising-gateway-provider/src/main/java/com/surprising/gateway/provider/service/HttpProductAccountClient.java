package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.web.client.HttpStatusCodeException;

@Service
public class HttpProductAccountClient implements ProductAccountClient {

    private static final String INTERNAL_SERVICE = "surprising-gateway";
    private static final String INTERNAL_AUDIENCE = "/api/v1/accounts/admin/product-balance-adjustments";
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public HttpProductAccountClient(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public ProductAccountAdjustment adjust(String accountType,
                                           long amountUnits,
                                           String referenceId,
                                           String reason,
                                           long userId,
                                           String asset) {
        GatewayProperties.BackendRoute accountRoute = properties.getRoutes().get("account");
        ProductLine line = ProductTransferCoordinator.productLine(accountType);
        GatewayProperties.BackendRoute route = accountRoute == null || !accountRoute.hasProductRoutes()
                ? null : accountRoute.resolve(line);
        if (route == null || route.getBaseUrl() == null || route.getBaseUrl().isBlank()
                || route.getTargetPrefix() == null || route.getTargetPrefix().isBlank()) {
            throw new IllegalStateException("account route is not configured for " + line);
        }
        String normalizedAsset = asset.trim().toUpperCase(Locale.ROOT);
        String normalizedReason = reason == null ? "" : reason;
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("accountType", accountType);
        payload.put("asset", normalizedAsset);
        payload.put("amountUnits", amountUnits);
        payload.put("referenceId", referenceId);
        payload.put("reason", normalizedReason);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service", INTERNAL_SERVICE);
        headers.set("X-Internal-Timestamp", Long.toString(timestamp));
        headers.set("X-Internal-Audience", INTERNAL_AUDIENCE);
        headers.set("X-Internal-Signature", signature(properties.getCustodyWallet().getSpotAccountInternalSecret(),
                timestamp, userId, accountType, normalizedAsset, amountUnits, referenceId, normalizedReason));
        URI target = URI.create(trimTrailingSlash(route.getBaseUrl()) + ensureLeadingSlash(route.getTargetPrefix())
                + "/admin/product-balance-adjustments");
        try {
            ResponseEntity<String> response = restTemplate.exchange(target, HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                return ProductAccountAdjustment.applied(response.getBody());
            }
            int status = response == null ? 0 : response.getStatusCode().value();
            if (isPermanentRejection(status)) {
                return ProductAccountAdjustment.rejected("account provider rejected adjustment HTTP " + status);
            }
            return ProductAccountAdjustment.unknown("account provider adjustment outcome is unknown HTTP " + status);
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            if (isPermanentRejection(status)) {
                return ProductAccountAdjustment.rejected("account provider rejected adjustment HTTP " + status);
            }
            return ProductAccountAdjustment.unknown("account provider adjustment outcome is unknown HTTP " + status);
        } catch (RestClientException ex) {
            return ProductAccountAdjustment.unknown("account provider adjustment outcome is unknown");
        }
    }

    String signature(String secret, long timestamp, long userId, String accountType, String asset,
                     long amountUnits, String referenceId, String reason) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("account provider internal secret is not configured");
        }
        String canonical = field(INTERNAL_SERVICE) + field(INTERNAL_AUDIENCE) + field(Long.toString(timestamp))
                + field(Long.toString(userId)) + field(accountType) + field(asset) + field(Long.toString(amountUnits))
                + field(referenceId) + field(reason);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("account provider internal signing failed", ex);
        }
    }

    private String field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    private boolean isPermanentRejection(int status) {
        return status == 400 || status == 409 || status == 422;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String ensureLeadingSlash(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }
}
