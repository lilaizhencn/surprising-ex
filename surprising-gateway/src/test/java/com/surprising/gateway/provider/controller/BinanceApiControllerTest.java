package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.controller.AccountRequestHandler;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import com.surprising.instrument.provider.controller.InstrumentRequestHandler;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.controller.OrderRequestHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;

class BinanceApiControllerTest {

    @Test
    void exposesBinanceProductApiNamespaces() throws NoSuchMethodException {
        RequestMapping mapping = BinanceApiController.class
                .getMethod("handle", HttpServletRequest.class, byte[].class)
                .getAnnotation(RequestMapping.class);

        assertThat(List.of(mapping.path()))
                .contains("/fapi/v1/**", "/dapi/v1/**", "/eapi/v1/**");
    }

    @Test
    void assetTransferUsesSharedGatewayCoordinatorRoute() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setQuantityScale(6);
        properties.getBinanceApi().setSymbolScales(Map.of("USDT", scale));
        AuthService authService = bearerAuth();
        AccountRequestHandler accountRequests = mock(AccountRequestHandler.class);
        when(accountRequests.transfer(any(ProductTransferRequest.class)))
                .thenReturn(new ProductTransferResponse(7123L, 42L, AccountType.SPOT, AccountType.USDT_PERPETUAL, "USDT", 1_250_000L, "ref", "COMPLETED", null, null, Instant.now()));

        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                accountRequests, mock(MatchingMarketDataService.class),
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
        Map<?, ?> transfer = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(String.valueOf(transfer.get("tranId"))).isEqualTo("7123");
        ArgumentCaptor<ProductTransferRequest> captor = ArgumentCaptor.forClass(ProductTransferRequest.class);
        verify(accountRequests).transfer(captor.capture());
        ProductTransferRequest captured = captor.getValue();
        assertThat(captured.userId()).isEqualTo(1001L);
        assertThat(captured.sourceAccountType()).isEqualTo(AccountType.FUNDING);
        assertThat(captured.targetAccountType()).isEqualTo(AccountType.USDT_PERPETUAL);
        assertThat(captured.amountUnits()).isEqualTo(1250000L);
    }

    @Test
    void supportsBinanceCoinMFuturesTransferAliases() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setQuantityScale(6);
        properties.getBinanceApi().setSymbolScales(Map.of("USDT", scale));
        AccountRequestHandler accountRequests = mock(AccountRequestHandler.class);
        when(accountRequests.transfer(any(ProductTransferRequest.class)))
                .thenReturn(new ProductTransferResponse(7124L, 42L, AccountType.SPOT, AccountType.COIN_PERPETUAL, "USDT", 1_000_000L, "ref", "COMPLETED", null, null, Instant.now()));

        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                accountRequests, mock(MatchingMarketDataService.class),
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

        ArgumentCaptor<ProductTransferRequest> captor = ArgumentCaptor.forClass(ProductTransferRequest.class);
        verify(accountRequests).transfer(captor.capture());
        ProductTransferRequest captured = captor.getValue();
        assertThat(captured.sourceAccountType()).isEqualTo(AccountType.FUNDING);
        assertThat(captured.targetAccountType()).isEqualTo(AccountType.COIN_PERPETUAL);
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
                        withdrawalId, "SUBMITTED", "wallet-withdrawal-1", BigDecimal.ONE, now, now, "25", "0", "25", 0L));
        CustodyWalletClient custodyWalletClient = mock(CustodyWalletClient.class);

        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                mock(AccountRequestHandler.class), mock(MatchingMarketDataService.class),
                mock(GatewayApiKeyService.class), verification, authService, compliance,
                custodyWalletClient, withdrawalService, new ObjectMapper());

        MockHttpServletRequest request = withdrawalRequest();
        request.addHeader("X-Security-Email-Code", "email-code");
        request.addHeader("X-Security-TOTP-Code", "totp-code");

        var response = controller.handle(request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(verification).verify(eq(1001L), eq("WITHDRAWAL"), eq("email-code"), eq("totp-code"), any());
        ArgumentCaptor<CustodyWithdrawalService.WithdrawalRequest> requestCaptor =
                ArgumentCaptor.forClass(CustodyWithdrawalService.WithdrawalRequest.class);
        verify(withdrawalService).submit(eq(1001L), eq("withdraw-1"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().custodyAddressId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(custodyWalletClient);
    }

    @Test
    void allOrdersReadsHistoryRouteAndReturnsAscendingBinanceOrderList() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        properties.getBinanceApi().setSymbolAliases(Map.of("SPOT:BTCUSDT", "1"));

        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        OrderRequestHandler orderRequests = mock(OrderRequestHandler.class);
        OrderResponse order2 = new OrderResponse(2L, 1001L, "b", "1", OrderSide.SELL, OrderType.LIMIT,
                TimeInForce.GTC, 12345L, 2000L, 2000L, 0L, MarginMode.CROSS, PositionSide.NET,
                0L, 0L, false, false, OrderStatus.FILLED, null,
                Instant.parse("2026-08-01T00:00:02Z"), Instant.parse("2026-08-01T00:00:02Z"),
                0L, "0", "0");
        OrderResponse order1 = new OrderResponse(1L, 1001L, "a", "1", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 12300L, 1000L, 0L, 1000L, MarginMode.CROSS, PositionSide.NET,
                0L, 0L, false, false, OrderStatus.CANCELED, null,
                Instant.parse("2026-08-01T00:00:01Z"), Instant.parse("2026-08-01T00:00:01Z"),
                0L, "0", "0");
        when(orderRequests.historyOrders(eq(1001L), eq("1"), eq(100), eq(1L), eq(1754006400000L), any(), any(), any(), any()))
                .thenReturn(new OrderQueryResponse(2, List.of(order2, order1)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/allOrders");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        request.setParameter("orderId", "1");
        request.setParameter("startTime", "1754006400000");

        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), orderRequests,
                mock(AccountRequestHandler.class), mock(MatchingMarketDataService.class),
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(CustodyWithdrawalService.class), new ObjectMapper());

        var response = controller.handle(request, null);
        List<Map<String, Object>> orders = new ObjectMapper().readValue(response.getBody(), List.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(orders).extracting(row -> ((Number) row.get("orderId")).longValue())
                .containsExactly(1L, 2L);
        assertThat(orders.getFirst().get("status")).isEqualTo("CANCELED");
        verify(orderRequests).historyOrders(eq(1001L), eq("1"), eq(100), eq(1L), eq(1754006400000L), any(), any(), any(), any());
    }

    @Test
    void accountReportsWithdrawalCapabilityOnlyWhenWalletAndKycAreReady() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setEnabled(true);
        AccountRequestHandler accountRequests = mock(AccountRequestHandler.class);
        when(accountRequests.balances(1001L)).thenReturn(new BalanceQueryResponse(0, List.of()));
        ComplianceService compliance = mock(ComplianceService.class);
        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "STANDARD", "PENDING", "US",
                "PASSPORT", "SELF", null, null, null, null, null, null, null));
        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                accountRequests, mock(MatchingMarketDataService.class),
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
        AuthService authService = bearerAuth();
        CustodyWalletClient walletClient = mock(CustodyWalletClient.class);
        when(walletClient.chains()).thenReturn(List.of(
                Map.of("chain", "TRX", "enabled", true, "withdrawalEnabled", true,
                        "assetSymbols", List.of("USDT")),
                Map.of("chain", "ETH", "enabled", true, "withdrawalEnabled", false,
                        "assetSymbols", List.of("USDT"))));
        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                mock(AccountRequestHandler.class), mock(MatchingMarketDataService.class),
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class), authService,
                mock(ComplianceService.class), walletClient, mock(CustodyWithdrawalService.class), new ObjectMapper());

        MockHttpServletRequest configRequest = new MockHttpServletRequest(
                "GET", "/sapi/v1/capital/config/getall");
        configRequest.addHeader("Authorization", "Bearer token");
        ResponseEntity<byte[]> config = controller.handle(configRequest, null);
        List<Map<String, Object>> coins = new ObjectMapper().readValue(config.getBody(), List.class);

        assertThat(config.getStatusCode().value()).isEqualTo(200);
        assertThat(coins).hasSize(1);
        assertThat((List<?>) coins.getFirst().get("networkList")).hasSize(2);
        assertThat(((List<Map<String, Object>>) coins.getFirst().get("networkList")).get(1))
                .containsEntry("withdrawEnable", false);

        MockHttpServletRequest statusRequest = new MockHttpServletRequest(
                "GET", "/sapi/v1/account/status");
        statusRequest.addHeader("Authorization", "Bearer token");
        Map<String, Object> status = new ObjectMapper().readValue(
                controller.handle(statusRequest, null).getBody(), Map.class);

        assertThat(status).containsEntry("data", "Normal");
    }

    @Test
    void exposesBookTickerWithBinanceShape() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        properties.getBinanceApi().setSymbolAliases(Map.of("SPOT:BTCUSDT", "1"));
        MatchingMarketDataService marketData = mock(MatchingMarketDataService.class);
        when(marketData.orderBookSnapshot(eq("1"), anyInt())).thenReturn(
                new OrderBookSnapshotResponse("1", 100L, 5,
                        List.of(new OrderBookLevel(12345L, 2000L, 1L)),
                        List.of(new OrderBookLevel(12355L, 3000L, 1L)),
                        Instant.now()));
        BinanceApiController controller = new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                mock(AccountRequestHandler.class), marketData,
                mock(GatewayApiKeyService.class), mock(SensitiveActionVerificationService.class),
                bearerAuth(), mock(ComplianceService.class), mock(CustodyWalletClient.class),
                mock(CustodyWithdrawalService.class), new ObjectMapper());

        MockHttpServletRequest bookRequest = new MockHttpServletRequest("GET", "/api/v3/ticker/bookTicker");
        bookRequest.addHeader("Authorization", "Bearer token");
        bookRequest.setParameter("symbol", "BTCUSDT");
        Map<String, Object> book = new ObjectMapper().readValue(
                controller.handle(bookRequest, null).getBody(), Map.class);
        assertThat(book).containsEntry("bidPrice", "123.45")
                .containsEntry("bidQty", "2")
                .containsEntry("askPrice", "123.55")
                .containsEntry("askQty", "3");
    }

    private BinanceApiController controller(GatewayProperties properties, AuthService authService,
                                             SensitiveActionVerificationService verification,
                                             CustodyWithdrawalService withdrawalService) {
        ComplianceService compliance = mock(ComplianceService.class);
        when(compliance.kyc(1001L)).thenReturn(new KycProfile(1001L, "BASIC", "VERIFIED", "US",
                "PASSPORT", "self", null, null, null, null, null, null, null));
        return new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class), mock(OrderRequestHandler.class),
                mock(AccountRequestHandler.class), mock(MatchingMarketDataService.class),
                mock(GatewayApiKeyService.class), verification, authService, compliance,
                mock(CustodyWalletClient.class), withdrawalService, new ObjectMapper());
    }

    private GatewayProperties withdrawalProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getBinanceApi().setEnabled(true);
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
        request.addParameter("networkRevision", "0");
        return request;
    }
}
