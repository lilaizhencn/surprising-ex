package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.controller.AccountInternalController;
import com.surprising.gateway.provider.config.BusinessEndpointConfiguration;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class LocalBusinessApiTest {

    private final com.surprising.websocket.provider.service.SubscriptionRegistry websocket = mock(com.surprising.websocket.provider.service.SubscriptionRegistry.class);

    private final com.surprising.trading.order.service.OrderRequestService orders = mock(com.surprising.trading.order.service.OrderRequestService.class);

    private final com.surprising.trading.matching.service.MatchingMarketDataService market = mock(com.surprising.trading.matching.service.MatchingMarketDataService.class);

    private final com.surprising.account.provider.service.AccountRequestService accounts = mock(com.surprising.account.provider.service.AccountRequestService.class);

    private final com.surprising.instrument.provider.service.InstrumentRequestService instruments = mock(com.surprising.instrument.provider.service.InstrumentRequestService.class);

    private final com.surprising.trading.maintenance.AdminMaintenanceRequestService maintenance = mock(com.surprising.trading.maintenance.AdminMaintenanceRequestService.class);

    private final AccountProperties accountProperties = new AccountProperties();

    private final TradingOrderProperties tradingProperties = new TradingOrderProperties();

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    {
        accountProperties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        tradingProperties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
    }

    private LocalBusinessApi api() {
        return new LocalBusinessApi(new TradingLocalRoutes(maintenance, mock(com.surprising.trading.trigger.service.TriggerOrderRequestService.class), mock(com.surprising.trading.trigger.service.AdminTriggerOrderRequestService.class), mock(com.surprising.trading.order.service.LeverageRequestService.class), mock(com.surprising.trading.order.service.TradingFeeRequestService.class), orders, mock(com.surprising.trading.order.service.AdminOrderRequestService.class), mock(com.surprising.trading.order.service.InstrumentCoreSyncService.class), market), new AccountLocalRoutes(accounts), new InstrumentLocalRoutes(instruments), mapper, Validation.buildDefaultValidatorFactory().getValidator(), websocket, accountProperties, tradingProperties);
    }

    private ResponseEntity<byte[]> invoke(String service, String path, HttpMethod method, HttpHeaders headers, String body) {
        return api().invoke(service, URI.create("local:" + path), method, headers, body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofMillis(20));
    }

    private HttpHeaders userHeaders() {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", "42");
        headers.set("X-Product-Line", "LINEAR_PERPETUAL");
        return headers;
    }

    @Test
    void websocketMetricsCallsLocalControllerWithTrustedAdminIdentity() {
        assertThat(LocalBusinessApi.isLocalService("websocket-admin")).isTrue();
        var headers = new HttpHeaders();
        headers.set("X-Admin-User-Id", "7");
        headers.set("X-Admin-Username", "admin");
        assertThat(invoke("websocket-admin", "/api/v1/admin/websocket/metrics", HttpMethod.GET, headers, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(websocket).metrics("7", "admin");
        assertThat(invoke("websocket-admin", "/api/v1/admin/websocket/metrics", HttpMethod.POST, headers, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invoke("websocket-admin", "/api/v1/admin/websocket/unknown", HttpMethod.GET, headers, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoMoreInteractions(websocket);
    }

    @Test
    void websocketMetricsRejectsMissingAdminAndForgedRawHttpHeaders() throws Exception {
        assertThat(invoke("websocket-admin", "/api/v1/admin/websocket/metrics", HttpMethod.GET, userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(websocket);
        var proxy = mock(com.surprising.gateway.provider.service.GatewayProxyService.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.surprising.gateway.provider.controller.GatewayProxyController(proxy)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/websocket/metrics")
                .header("X-Admin-User-Id", "7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verifyNoInteractions(proxy);

    }

    @Test
    void publicBookUsesLocalControllerAndDefaultDepthWithoutUserIdentity() {
        assertThat(LocalBusinessApi.isLocalService("trading-market")).isTrue();
        var response = invoke("trading-market", "/api/v1/trading/market/orderbook?symbol=BTC-USDT", HttpMethod.GET, new HttpHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(market).orderBookSnapshot("BTC-USDT", 30);
    }

    @Test
    void missingBookSymbolDoesNotReachController() {
        var response = invoke("trading-market", "/api/v1/trading/market/orderbook", HttpMethod.GET, new HttpHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(market);
    }

    @Test
    void accountQueryCallsLocalControllerWithAuthenticatedIdentity() {
        var result = invoke("account", "/api/v1/accounts/balance?asset=USDT", HttpMethod.GET, userHeaders(), null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accounts).balance(42, "USDT");
    }

    @Test
    void forgedQueryAndNestedBatchIdentityNeverReachBusinessCode() {
        assertThat(invoke("account", "/api/v1/accounts/balance?userId=7&asset=USDT", HttpMethod.GET, userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invoke("trading", "/api/v1/trading/orders/batch", HttpMethod.POST, userHeaders(), "{\"orders\":[{\"userId\":7}]}").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(accounts, orders);
    }

    @Test
    void publicRouteCannotReachAdminControllerByAppendingAdminPath() {
        assertThat(invoke("instrument", "/api/v1/instruments/admin/list", HttpMethod.GET, userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invoke("instrument", "/api/v1/instruments/%61dmin/list", HttpMethod.GET, userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(instruments);
    }

    @Test
    void malformedBodyAndMissingRequiredParametersAreRejectedBeforeInvocation() {
        assertThat(invoke("trading", "/api/v1/trading/orders", HttpMethod.POST, userHeaders(), "{").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invoke("account", "/api/v1/accounts/balance", HttpMethod.GET, userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(accounts, orders);
    }

    @Test
    void specificRouteWinsOverOrderIdPatternAndPreservesQueryDecoding() {
        invoke("trading", "/api/v1/trading/orders/by-client-order-id?userId=42&clientOrderId=a%2Bb", HttpMethod.GET, userHeaders(), null);
        verify(orders).getByClientOrderId(42, "a+b", null);
    }

    @Test
    void asyncStatusAndPendingOutcomeArePreserved() {
        when(orders.cancel(any())).thenReturn(CompletableFuture.completedFuture(ResponseEntity.accepted().build()));
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST, userHeaders(), "{\"userId\":42,\"orderId\":1}").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var pending = new CompletableFuture<ResponseEntity<com.surprising.trading.api.model.OrderCommandReceipt>>();
        when(orders.cancel(any())).thenReturn(pending);
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST, userHeaders(), "{\"userId\":42,\"orderId\":2}").getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(pending.isCancelled()).isFalse();
    }

    @Test
    void failedAsyncCommandKeepsConflictStatus() {
        when(orders.cancel(any())).thenReturn(CompletableFuture.failedFuture(new ResponseStatusException(HttpStatus.CONFLICT, "order already completed")));
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST, userHeaders(), "{\"userId\":42,\"orderId\":1}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void conflictingProductSelectorsFailClosedIncludingNestedCommands() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Product-Line", "LINEAR_PERPETUAL");
        request.addParameter("accountType", "SPOT");
        assertThatThrownBy(() -> api().validateProductSelectors(request, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> api().validateProductSelectors(new MockHttpServletRequest(), "{\"orders\":[{\"productLine\":\"OPTION\"}]}".getBytes())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(orders, accounts);
    }

    @Test
    void mergedDomainsMustUseTheSameProductLine() {
        accountProperties.getKafka().setProductLine(ProductLine.OPTION);
        tradingProperties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> api().validateConfiguration()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maintenanceStillRunsItsExplicitAuthorizationBeforeCallingService() {
        var headers = userHeaders();
        headers.set("X-Admin-User-Id", "7");
        invoke("trading-orders", "/api/v1/admin/trading/orders/maintenance?productLine=LINEAR_PERPETUAL", HttpMethod.GET, headers, null);
        var sequence = inOrder(maintenance);
        sequence.verify(maintenance).authorize("7", "LINEAR_PERPETUAL", ProductLine.LINEAR_PERPETUAL);
        sequence.verify(maintenance).list(0);
    }

    @Test
    void localDispatchCannotDependOnHttpControllers() {
        for (Class<?> entry : java.util.List.of(LocalBusinessApi.class, TradingLocalRoutes.class,
                AccountLocalRoutes.class, InstrumentLocalRoutes.class)) {
            for (var field : entry.getDeclaredFields()) {
                assertThat(field.getType().isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestController.class))
                        .as("%s.%s must depend on a service", entry.getSimpleName(), field.getName()).isFalse();
            }
        }
    }

    @Test
    void everyPublicBusinessEndpointHasAnExplicitLocalRoute() throws Exception {
        // Frozen from the original HTTP mappings before deleting duplicate public controllers.
        var expected = java.nio.file.Files.readAllLines(java.nio.file.Path.of("src/test/resources/local-business-route-paths.txt"));
        var actual = new java.util.HashSet<String>();
        for (Class<?> routes : java.util.List.of(TradingLocalRoutes.class, AccountLocalRoutes.class, InstrumentLocalRoutes.class, LocalBusinessApi.class)) {
            for (var field : routes.getDeclaredFields()) {
                if (field.getType() == org.springframework.web.util.pattern.PathPattern.class) {
                    field.setAccessible(true);
                    actual.add(((org.springframework.web.util.pattern.PathPattern) field.get(null)).getPatternString());
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void rawBusinessUrlsRejectForgedIdentityHeadersWithoutInternalCredential() throws Exception {
        var interceptor = new BusinessEndpointConfiguration("test-internal-token");
        var handler = new org.springframework.web.method.HandlerMethod(new AccountInternalController(new com.surprising.account.provider.service.AccountRequestService(null, null, accountProperties)), AccountInternalController.class.getMethod("balance", long.class, String.class));
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts/balance");
        request.addHeader("X-Admin-User-Id", "7");
        request.addHeader("X-User-Id", "42");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        request.addHeader("X-Business-Internal-Token", "test-internal-token");
        assertThat(interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), handler)).isTrue();
    }
}
