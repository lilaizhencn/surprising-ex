package com.surprising.gateway.provider.service;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.ProductTransferInternalAuth;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PendingProductTransfersRequest;
import com.surprising.account.api.model.PendingProductTransfersResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public final class HttpProductAccountClient implements ProductAccountClient {

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public HttpProductAccountClient(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public ProductAccountAdjustment transferOut(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_OUT_PATH, request);
    }

    @Override
    public ProductAccountAdjustment transferIn(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_IN_PATH, request);
    }

    @Override
    public ProductAccountAdjustment completeTransfer(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_COMPLETE_PATH, request);
    }

    @Override
    public List<ProductTransferOperationRequest> pendingTransfers(ProductLine productLine, int limit) {
        PendingProductTransfersRequest request = new PendingProductTransfersRequest(productLine, limit);
        String audience = AccountApiPaths.TRANSFER_PENDING_PATH;
        long timestamp = Instant.now().getEpochSecond();
        try {
            ResponseEntity<PendingProductTransfersResponse> response = restTemplate.exchange(
                    target(productLine, audience), HttpMethod.POST,
                    new HttpEntity<>(request, headers(audience, timestamp,
                            ProductTransferInternalAuth.canonical(audience, timestamp, request))),
                    PendingProductTransfersResponse.class);
            PendingProductTransfersResponse body = response.getBody();
            return response.getStatusCode().is2xxSuccessful() && body != null ? body.transfers() : List.of();
        } catch (RestClientException exception) {
            throw new IllegalStateException("pending transfer runtime query failed for " + productLine, exception);
        }
    }

    private ProductAccountAdjustment operation(String accountType, String audience,
                                               ProductTransferOperationRequest request) {
        ProductLine productLine = ProductTransferCoordinator.productLine(AccountType.valueOf(accountType));
        long timestamp = Instant.now().getEpochSecond();
        try {
            ResponseEntity<String> response = restTemplate.exchange(target(productLine, audience), HttpMethod.POST,
                    new HttpEntity<>(request, headers(audience, timestamp,
                            ProductTransferInternalAuth.canonical(audience, timestamp, request))), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return ProductAccountAdjustment.applied(response.getBody());
            }
            return status(response.getStatusCode().value());
        } catch (HttpStatusCodeException exception) {
            return status(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            return ProductAccountAdjustment.unknown("account provider transfer outcome is unknown");
        }
    }

    private ProductAccountAdjustment status(int status) {
        if (status == 400 || status == 409 || status == 422) {
            return ProductAccountAdjustment.rejected("account provider rejected transfer HTTP " + status);
        }
        return ProductAccountAdjustment.unknown("account provider transfer outcome is unknown HTTP " + status);
    }

    private HttpHeaders headers(String audience, long timestamp, String canonical) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service", ProductTransferInternalAuth.SERVICE);
        headers.set("X-Internal-Timestamp", Long.toString(timestamp));
        headers.set("X-Internal-Audience", audience);
        headers.set("X-Internal-Signature", sign(canonical));
        return headers;
    }

    private URI target(ProductLine productLine, String audience) {
        GatewayProperties.BackendRoute account = properties.getRoutes().get("account");
        GatewayProperties.ProductRoute configured = account == null ? null : account.getProductRoutes().get(productLine);
        GatewayProperties.BackendRoute route = configured == null || configured.getBaseUrl() == null
                || configured.getBaseUrl().isBlank() ? null : account.resolve(productLine);
        if (route == null || route.getBaseUrl() == null || route.getBaseUrl().isBlank()) {
            throw new IllegalStateException("account route is not configured for " + productLine);
        }
        return URI.create(trimTrailingSlash(route.getBaseUrl()) + audience);
    }

    private String sign(String canonical) {
        String secret = properties.getCustodyWallet().getSpotAccountInternalSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("account provider internal secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("account provider internal signing failed", exception);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
