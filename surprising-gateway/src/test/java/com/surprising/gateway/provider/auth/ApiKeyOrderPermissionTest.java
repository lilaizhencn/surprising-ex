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
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.controller.BinanceApiController;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import com.surprising.trading.order.service.OrderService;
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
    final OrderService orderService = mock(OrderService.class);

    @Test void readOnlyKeyCannotEnterTheCancellationBusiness() {
        record("READ");
        var response = controller().handle(request(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(orderService);
    }

    @Test void tradeKeyCancellationUsesTheInternalPostContract() {
        record("READ,TRADE");
        UUID cmdId = UUID.randomUUID();
        when(orderService.cancelCommandAsync(any(CancelOrderRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new OrderCommandReceipt(
                        cmdId, "ACCEPTED", "OK", "ok",
                        OrderCommandReceipt.commandResultUrl(cmdId),
                        List.of(), null, null)));
        assertThat(controller().handle(request(), null).getStatusCode().value()).isEqualTo(200);
        verify(orderService).cancelCommandAsync(any(CancelOrderRequest.class));
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
                mock(InstrumentService.class),
                orderService,
                mock(AccountService.class),
                mock(MatchingMarketDataService.class),
                keys, null, auth, null, null, null, new ObjectMapper());
    }
}
