package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class BinanceApiControllerTest {

    @Test
    void assetTransferUsesSharedGatewayCoordinatorRoute() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setQuantityScale(6);
        properties.getBinanceApi().setSymbolScales(Map.of("USDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = bearerAuth();
        when(proxy.proxyCompat(anyString(), eq("/transfers"), isNull(), eq(HttpMethod.POST), any(), any(), eq(1001L)))
                .thenReturn(ResponseEntity.ok("{\"transferId\":7123,\"status\":\"COMPLETED\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class), mock(CustodyWalletClient.class),
                mock(CustodyWithdrawalService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sapi/v1/asset/transfer");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("type", "MAIN_UMFUTURE");
        request.setParameter("asset", "USDT");
        request.setParameter("amount", "1.25");
        request.setParameter("clientTranId", "binance-transfer-1");

        ResponseEntity<byte[]> response = controller.handle(request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new ObjectMapper().readValue(response.getBody(), Map.class))
                .containsEntry("tranId", 7123);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(proxy).proxyCompat(anyString(), eq("/transfers"), isNull(), eq(HttpMethod.POST), any(), body.capture(),
                eq(1001L));
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8))
                .contains("\"sourceAccountType\":\"FUNDING\"")
                .contains("\"targetAccountType\":\"USDT_PERPETUAL\"")
                .contains("\"amountUnits\":1250000");
    }

    @Test
    void supportsBinanceCoinMFuturesTransferAliases() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setQuantityScale(6);
        properties.getBinanceApi().setSymbolScales(Map.of("USDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        when(proxy.proxyCompat(anyString(), eq("/transfers"), isNull(), eq(HttpMethod.POST), any(), any(), eq(1001L)))
                .thenReturn(ResponseEntity.ok("{\"transferId\":7124,\"status\":\"COMPLETED\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), bearerAuth(),
                mock(ComplianceService.class), mock(CustodyWalletClient.class),
                mock(CustodyWithdrawalService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sapi/v1/asset/transfer");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("type", "MAIN_CMFUTURE");
        request.setParameter("asset", "USDT");
        request.setParameter("amount", "1");
        request.setParameter("clientTranId", "coinm-transfer-1");

        controller.handle(request, null);

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(proxy).proxyCompat(anyString(), eq("/transfers"), isNull(), eq(HttpMethod.POST), any(), body.capture(),
                eq(1001L));
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8))
                .contains("\"sourceAccountType\":\"FUNDING\"")
                .contains("\"targetAccountType\":\"COIN_PERPETUAL\"");
    }

    @Test
    void rejectsWithdrawBeforeCallingCustodyWhenSensitiveVerificationFails() {
        GatewayProperties properties = withdrawalProperties();
        AuthService authService = bearerAuth();
        SensitiveActionVerificationService verification = mock(SensitiveActionVerificationService.class);
        when(verification.verify(eq(1001L), eq("WITHDRAWAL"), eq("email-code"), eq("totp-code"), any()))
                .thenReturn(false);
        CustodyWithdrawalService withdrawalService = mock(CustodyWithdrawalService.class);
        BinanceApiController controller = controller(properties, authService, verification, withdrawalService);

        MockHttpServletRequest request = withdrawalRequest();
        request.addHeader("X-Security-Email-Code", "email-code");
        request.addHeader("X-Security-TOTP-Code", "totp-code");

        var response = controller.handle(request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(428);
        verify(withdrawalService, never()).submit(any(Long.class), anyString(), any());
    }

    @Test
    void acceptsVerifiedWithdrawAndSubmitsCustodyRequest() {
        GatewayProperties properties = withdrawalProperties();
        AuthService authService = bearerAuth();
        SensitiveActionVerificationService verification = mock(SensitiveActionVerificationService.class);
        when(verification.verify(eq(1001L), eq("WITHDRAWAL"), eq("email-code"), eq("totp-code"), any()))
                .thenReturn(true);
        ComplianceService compliance = mock(ComplianceService.class);
        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "BASIC", "VERIFIED", "US",
                "PASSPORT", "self", null, null, null, null, null, null, null));
        CustodyWithdrawalService withdrawalService = mock(CustodyWithdrawalService.class);
        UUID withdrawalId = UUID.randomUUID();
        Instant now = Instant.now();
        when(withdrawalService.submit(eq(1001L), eq("withdraw-1"), any()))
                .thenReturn(new CustodyWithdrawalService.WithdrawalResponse(
                        withdrawalId, "SUBMITTED", "wallet-withdrawal-1", BigDecimal.ONE, now, now));
        BinanceApiController controller = new BinanceApiController(properties, mock(GatewayProxyService.class),
                mock(GatewayApiKeyService.class), verification, authService, compliance,
                mock(CustodyWalletClient.class), withdrawalService, new ObjectMapper());

        MockHttpServletRequest request = withdrawalRequest();
        request.addHeader("X-Security-Email-Code", "email-code");
        request.addHeader("X-Security-TOTP-Code", "totp-code");

        var response = controller.handle(request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(verification).verify(eq(1001L), eq("WITHDRAWAL"), eq("email-code"), eq("totp-code"), any());
        verify(withdrawalService).submit(eq(1001L), eq("withdraw-1"), any());
    }

    @Test
    void allOrdersReadsHistoryRouteAndReturnsAscendingBinanceOrderList() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));

        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        String payload = "{\"orders\":["
                + "{\"orderId\":2,\"symbol\":\"BTCUSDT\",\"clientOrderId\":\"b\","
                + "\"createdAt\":\"2026-08-01T00:00:02Z\",\"orderType\":\"LIMIT\","
                + "\"timeInForce\":\"GTC\",\"side\":\"SELL\",\"status\":\"FILLED\","
                + "\"priceTicks\":12345,\"quantitySteps\":2000,\"executedQuantitySteps\":2000},"
                + "{\"orderId\":1,\"symbol\":\"BTCUSDT\",\"clientOrderId\":\"a\","
                + "\"createdAt\":\"2026-08-01T00:00:01Z\",\"orderType\":\"LIMIT\","
                + "\"timeInForce\":\"GTC\",\"side\":\"BUY\",\"status\":\"CANCELED\","
                + "\"priceTicks\":12300,\"quantitySteps\":1000,\"executedQuantitySteps\":0}]}";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/allOrders");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        request.setParameter("orderId", "1");
        request.setParameter("startTime", "1754006400000");
        when(proxy.proxyCompat(anyString(), eq("/history"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), eq(1001L)))
                .thenReturn(ResponseEntity.ok(payload.getBytes(StandardCharsets.UTF_8)));

        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(CustodyWithdrawalService.class), new ObjectMapper());

        var response = controller.handle(request, null);
        List<Map<String, Object>> orders = new ObjectMapper().readValue(response.getBody(), List.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(orders).extracting(row -> ((Number) row.get("orderId")).longValue())
                .containsExactly(1L, 2L);
        assertThat(orders.getFirst().get("status")).isEqualTo("CANCELED");
        verify(proxy).proxyCompat(anyString(), eq("/history"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), eq(1001L));
    }

    @Test
    void ticker24hrConvertsFactSummaryToBinanceFields() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        String payload = "{\"symbol\":\"BTCUSDT\",\"firstTradeId\":10,\"lastTradeId\":11,"
                + "\"tradeCount\":2,\"openPriceTicks\":10000,\"highPriceTicks\":11000,"
                + "\"lowPriceTicks\":9900,\"lastPriceTicks\":10500,\"volumeSteps\":\"3000\","
                + "\"quoteVolumeTicksSteps\":\"31000000\",\"lastQuantitySteps\":\"2000\","
                + "\"openTime\":\"2026-08-01T00:00:00Z\",\"closeTime\":\"2026-08-01T00:01:00Z\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/ticker/24hr");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        when(proxy.proxyCompat(anyString(), eq("/ticker-24hr"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull()))
                .thenReturn(ResponseEntity.ok(payload.getBytes(StandardCharsets.UTF_8)));
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(CustodyWithdrawalService.class), new ObjectMapper());

        var response = controller.handle(request, null);
        Map<String, Object> ticker = new ObjectMapper().readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ticker).containsEntry("symbol", "BTCUSDT")
                .containsEntry("priceChange", "5")
                .containsEntry("priceChangePercent", "5")
                .containsEntry("lastPrice", "105")
                .containsEntry("volume", "3")
                .containsEntry("quoteVolume", "310");
        verify(proxy).proxyCompat(anyString(), eq("/ticker-24hr"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull());
    }

    @Test
    void tickerPriceReadsLatestTradeFromPublicMarketRoute() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        when(proxy.proxyCompat(anyString(), eq("/latest-trade"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull()))
                .thenReturn(ResponseEntity.ok("{\"priceTicks\":12345}".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/ticker/price");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(CustodyWithdrawalService.class), new ObjectMapper());

        var response = controller.handle(request, null);
        Map<String, Object> ticker = new ObjectMapper().readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ticker).containsEntry("symbol", "BTCUSDT").containsEntry("price", "123.45");
    }

    @Test
    void accountReportsWithdrawalCapabilityOnlyWhenWalletAndKycAreReady() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        when(proxy.proxyCompat(anyString(), eq("/balances"), eq("userId=1001"), eq(HttpMethod.GET),
                any(), isNull(), eq(1001L)))
                .thenReturn(ResponseEntity.ok("{\"balances\":[]}".getBytes(StandardCharsets.UTF_8)));
        ComplianceService compliance = mock(ComplianceService.class);
        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "STANDARD", "PENDING", "US",
                "PASSPORT", "SELF", null, null, null, null, null, null, null));
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), bearerAuth(),
                compliance, mock(CustodyWalletClient.class), mock(CustodyWithdrawalService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/account");
        request.addHeader("Authorization", "Bearer token");

        Map<String, Object> pending = new ObjectMapper().readValue(
                controller.handle(request, null).getBody(), Map.class);

        assertThat(pending).containsEntry("canWithdraw", false).containsEntry("canDeposit", true);

        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "STANDARD", "VERIFIED", "US",
                "PASSPORT", "SELF", null, null, null, null, null, null, null));
        Map<String, Object> verified = new ObjectMapper().readValue(
                controller.handle(request, null).getBody(), Map.class);

        assertThat(verified).containsEntry("canWithdraw", true).containsEntry("canDeposit", true);
    }

    @Test
    void exposesConfiguredCapitalNetworksAndAccountStatus() throws Exception {
        GatewayProperties properties = withdrawalProperties();
        properties.getCustodyWallet().setEnabled(true);
        properties.getCustodyWallet().setAssetScales(Map.of("USDT", 6L));
        properties.getCustodyWallet().setWithdrawalAddressIds(Map.of(
                "TRX", UUID.randomUUID().toString(), "ETH", UUID.randomUUID().toString()));
        AuthService authService = bearerAuth();
        BinanceApiController controller = controller(properties, authService,
                mock(SensitiveActionVerificationService.class), mock(CustodyWithdrawalService.class));

        MockHttpServletRequest configRequest = new MockHttpServletRequest(
                "GET", "/sapi/v1/capital/config/getall");
        configRequest.addHeader("Authorization", "Bearer token");
        ResponseEntity<byte[]> config = controller.handle(configRequest, null);
        List<Map<String, Object>> coins = new ObjectMapper().readValue(config.getBody(), List.class);

        assertThat(config.getStatusCode().value()).isEqualTo(200);
        assertThat(coins).hasSize(1);
        assertThat((List<?>) coins.getFirst().get("networkList")).hasSize(2);

        MockHttpServletRequest statusRequest = new MockHttpServletRequest(
                "GET", "/sapi/v1/account/status");
        statusRequest.addHeader("Authorization", "Bearer token");
        Map<String, Object> status = new ObjectMapper().readValue(
                controller.handle(statusRequest, null).getBody(), Map.class);

        assertThat(status).containsEntry("data", "Normal");
    }

    private BinanceApiController controller(GatewayProperties properties, AuthService authService,
                                             SensitiveActionVerificationService verification,
                                             CustodyWithdrawalService withdrawalService) {
        ComplianceService compliance = mock(ComplianceService.class);
        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "BASIC", "VERIFIED", "US",
                "PASSPORT", "self", null, null, null, null, null, null, null));
        return new BinanceApiController(properties, mock(GatewayProxyService.class),
                mock(GatewayApiKeyService.class), verification, authService, compliance,
                mock(CustodyWalletClient.class), withdrawalService, new ObjectMapper());
    }

    private GatewayProperties withdrawalProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getBinanceApi().setEnabled(true);
        properties.getCustodyWallet().setWithdrawalAddressIds(
                Map.of("ETH", UUID.randomUUID().toString()));
        return properties;
    }

    private AuthService bearerAuth() {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        return authService;
    }

    private MockHttpServletRequest withdrawalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/sapi/v1/capital/withdraw/apply");
        request.addHeader("Authorization", "Bearer token");
        request.addParameter("coin", "USDT");
        request.addParameter("network", "ETH");
        request.addParameter("address", "0x1111111111111111111111111111111111111111");
        request.addParameter("amount", "1");
        request.addParameter("withdrawOrderId", "withdraw-1");
        return request;
    }
}
