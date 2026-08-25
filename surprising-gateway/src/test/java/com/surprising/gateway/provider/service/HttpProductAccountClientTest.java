package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

class HttpProductAccountClientTest {

    @Test
    void resolvesSourceRuntimeAndSignsDedicatedTransferOutPayload() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.BackendRoute account = new GatewayProperties.BackendRoute(
                "http://account-default:9086", "/api/v1/accounts", true);
        EnumMap<ProductLine, GatewayProperties.ProductRoute> productRoutes = new EnumMap<>(ProductLine.class);
        productRoutes.put(ProductLine.LINEAR_PERPETUAL,
                new GatewayProperties.ProductRoute("http://account-linear:9186", "/api/v1/accounts"));
        account.setProductRoutes(productRoutes);
        properties.setRoutes(Map.of("account", account));
        properties.getCustodyWallet().setSpotAccountInternalSecret("account-internal-secret-for-tests-32");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        HttpProductAccountClient client = new HttpProductAccountClient(properties, restTemplate);

        ProductTransferOperationRequest operation = operation();
        ProductAccountAdjustment result = client.transferOut("USDT_PERPETUAL", operation);

        assertThat(result.status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), request.capture(), eq(String.class));
        assertThat(uri.getValue()).isEqualTo(URI.create(
                "http://account-linear:9186" + AccountApiPaths.TRANSFER_OUT_PATH));
        assertThat(request.getValue().getBody()).isEqualTo(operation);
        String timestamp = request.getValue().getHeaders().getFirst("X-Internal-Timestamp");
        assertThat(request.getValue().getHeaders().getFirst("X-Internal-Service"))
                .isEqualTo("surprising-gateway");
        assertThat(request.getValue().getHeaders().getFirst("X-Internal-Audience"))
                .isEqualTo(AccountApiPaths.TRANSFER_OUT_PATH);
        assertThat(request.getValue().getHeaders().getFirst("X-Internal-Signature")).startsWith("v1=");
        assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp))).isLessThanOrEqualTo(1L);
    }

    @Test
    void inheritsAccountTargetPrefixWhenProductRouteOnlyOverridesBaseUrl() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.BackendRoute account = new GatewayProperties.BackendRoute(
                "http://account-default:9086", "/api/v1/accounts", true);
        account.setProductRoutes(Map.of(ProductLine.LINEAR_PERPETUAL,
                new GatewayProperties.ProductRoute("http://account-linear:9186", "")));
        properties.setRoutes(Map.of("account", account));
        properties.getCustodyWallet().setSpotAccountInternalSecret("account-internal-secret-for-tests-32");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        new HttpProductAccountClient(properties, restTemplate)
                .transferOut("USDT_PERPETUAL", operation());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue()).isEqualTo(URI.create(
                "http://account-linear:9186" + AccountApiPaths.TRANSFER_OUT_PATH));
    }

    @Test
    void mapsProviderClientErrorsToPermanentRejection() {
        GatewayProperties properties = propertiesWithLinearRoute();
        properties.getCustodyWallet().setSpotAccountInternalSecret("account-internal-secret-for-tests-32");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.CONFLICT,
                        "conflict", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        ProductAccountAdjustment result = new HttpProductAccountClient(properties, restTemplate)
                .transferIn("USDT_PERPETUAL", operation());

        assertThat(result.status()).isEqualTo(ProductAccountAdjustment.Status.REJECTED);
    }

    @Test
    void keepsAuthenticationAndRateLimitErrorsRecoverable() {
        GatewayProperties properties = propertiesWithLinearRoute();
        properties.getCustodyWallet().setSpotAccountInternalSecret("account-internal-secret-for-tests-32");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "rate limited", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        ProductAccountAdjustment result = new HttpProductAccountClient(properties, restTemplate)
                .transferIn("USDT_PERPETUAL", operation());

        assertThat(result.status()).isEqualTo(ProductAccountAdjustment.Status.UNKNOWN);
    }

    @Test
    void refusesToFallbackToAnUnscopedAccountRoute() {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(Map.of("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true)));
        properties.getCustodyWallet().setSpotAccountInternalSecret("account-internal-secret-for-tests-32");

        assertThatThrownBy(() -> new HttpProductAccountClient(properties, mock(RestTemplate.class))
                .transferOut("USDT_PERPETUAL", operation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("route is not configured");
    }

    @Test
    void refusesWhenTheSelectedProductRouteHasNoBaseUrl() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.BackendRoute account = new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true);
        account.setProductRoutes(Map.of(ProductLine.LINEAR_PERPETUAL,
                new GatewayProperties.ProductRoute("", "/api/v1/accounts")));
        properties.setRoutes(Map.of("account", account));

        assertThatThrownBy(() -> new HttpProductAccountClient(properties, mock(RestTemplate.class))
                .transferOut("USDT_PERPETUAL", operation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("route is not configured");
    }

    private GatewayProperties propertiesWithLinearRoute() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.BackendRoute account = new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true);
        account.setProductRoutes(Map.of(ProductLine.LINEAR_PERPETUAL,
                new GatewayProperties.ProductRoute("http://account-linear:9186", "/api/v1/accounts")));
        properties.setRoutes(Map.of("account", account));
        return properties;
    }

    private ProductTransferOperationRequest operation() {
        return new ProductTransferOperationRequest(7001L, 42L,
                ProductLine.LINEAR_PERPETUAL, ProductLine.SPOT,
                AccountType.USDT_PERPETUAL, AccountType.FUNDING,
                "USDT", 1_250L, "transfer-007", "test");
    }
}
