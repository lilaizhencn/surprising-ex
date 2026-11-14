package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.controller.AccountRequestHandler;
import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.service.TotpService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.controller.BinanceApiController;
import com.surprising.instrument.provider.controller.InstrumentRequestHandler;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.controller.OrderRequestHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ApiKeyOrderPermissionTest {
    final com.surprising.gateway.provider.auth.state.AuthStateFixture fixture = new com.surprising.gateway.provider.auth.state.AuthStateFixture();
    final String key = "sx_" + "k".repeat(24);
    final GatewayProperties properties = new GatewayProperties();
    final TotpService totp = new TotpService(properties);
    final GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
    final AuthService auth = mock(AuthService.class);
    final GatewayApiKeyService keys = new GatewayApiKeyService(repository, auth, totp, null, properties, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class), fixture.state);
    final OrderRequestHandler orderRequests = mock(OrderRequestHandler.class);

    @Test void readOnlyKeyCannotEnterTheCancellationBusiness() {
        record("READ");
        var response = controller().handle(request(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(orderRequests);
    }

    @Test void tradeKeyCancellationUsesTheInternalPostContract() {
        record("READ,TRADE");
        UUID cmdId = UUID.randomUUID();
        when(orderRequests.cancel(any(CancelOrderRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ResponseEntity.status(409).body(new OrderCommandReceipt(
                                cmdId, "REJECTED", "CONFLICT", "conflict",
                                OrderCommandReceipt.commandResultUrl(cmdId),
                                List.of(), null, null))));
        assertThat(controller().handle(request(), null).getStatusCode().value()).isEqualTo(409);
        verify(orderRequests).cancel(any(CancelOrderRequest.class));
    }

    private void record(String permissions) {
        var scale = new GatewayProperties.SymbolScale();
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        properties.getBinanceApi().setSymbolAliases(Map.of("SPOT:BTCUSDT", "1"));
        fixture.putApiKey(new GatewayApiKeyRepository.ApiKeyRecord(
                UUID.randomUUID(), 42L, key, totp.encryptSecret("secret"), "bot", permissions,
                "203.0.113.7", "ACTIVE", Instant.now(), null, null));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("DELETE", "/api/v3/order");
        request.setRemoteAddr("203.0.113.7"); request.addHeader("X-MBX-APIKEY", key);
        String query = "symbol=BTCUSDT&orderId=1&timestamp=" + System.currentTimeMillis();
        request.setQueryString(query + "&signature=" + keys.sign("secret", query));
        return request;
    }

    private BinanceApiController controller() {
        return new BinanceApiController(properties,
                mock(InstrumentRequestHandler.class),
                orderRequests,
                mock(AccountRequestHandler.class),
                mock(MatchingMarketDataService.class),
                keys, null, auth, null, null, null, new ObjectMapper());
    }
}
