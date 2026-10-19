package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.provider.config.AccountProperties;
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
    @Test void cancellationPlanAndBatchBindTheAuthenticatedUserAndPreserveKeys() {
        var plan = new com.surprising.trading.api.model.CancellationPlanResponse(42, ProductLine.SPOT,
                "1", java.util.List.of(9007199254740993L));
        when(orders.cancellationPlan(42, "1")).thenReturn(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plan));
        var response = invoke("trading", "/api/v1/trading/orders/cancellation-plan?instrumentId=1", HttpMethod.GET, userHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(orders).cancellationPlan(42, "1");
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(new java.util.UUID(8, 9),
                "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(), null, null);
        when(orders.cancellationBatch(42, "a+b")).thenReturn(ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(receipt));
        assertThat(invoke("trading", "/api/v1/trading/orders/cancellation-batches?batchKey=a%2Bb", HttpMethod.GET, userHeaders(), null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(orders).cancellationBatch(42, "a+b");
        for (var path : java.util.List.of("cancellation-plan?instrumentId=1", "cancellation-batches?batchKey=x")) {
            assertThat(invoke("trading", "/api/v1/trading/orders/"+path+"&userId=43", HttpMethod.GET, userHeaders(), null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test void batchCancelRequiresAnExplicitBatchKeyBeforeSubmitting() {
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(new java.util.UUID(9, 10),
                "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(91L), null, null);
        when(orders.cancelBatch(any())).thenReturn(CompletableFuture.completedFuture(ResponseEntity.accepted().body(receipt)));
        String invalid = "{\"orders\":[{\"userId\":42,\"orderId\":91}]}";
        assertThat(invoke("trading", "/api/v1/trading/orders/batch-cancel", HttpMethod.POST, userHeaders(), invalid).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(orders);
        String valid = "{\"batchKey\":\"fixed-1\",\"orders\":[{\"userId\":42,\"orderId\":91}]}";
        assertThat(invoke("trading", "/api/v1/trading/orders/batch-cancel", HttpMethod.POST, userHeaders(), valid).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test void amendmentPostRequiresAnExplicitClientRequestIdBeforeCallingTheOrderService() {
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(new java.util.UUID(9, 10),
                "TERMINAL", "NONE", "completed", null, java.util.List.of(), null, null);
        when(orders.amend(any())).thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(receipt)));
        String body = "{\"userId\":42,\"orderId\":91,\"newClientOrderId\":\"replacement-1\",\"priceTicks\":100,\"quantitySteps\":2}";
        assertThat(invoke("trading", "/api/v1/trading/orders/amend", HttpMethod.POST, userHeaders(), body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(orders);
        String valid = body.substring(0, body.length()-1) + ",\"clientRequestId\":\"edit-1\"}";
        assertThat(invoke("trading", "/api/v1/trading/orders/amend", HttpMethod.POST, userHeaders(), valid).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var request = org.mockito.ArgumentCaptor.forClass(com.surprising.trading.api.model.AmendOrderRequest.class);
        verify(orders).amend(request.capture());
        assertThat(request.getValue().clientRequestId()).isEqualTo("edit-1");
    }

    @Test void amendmentLookupBindsAuthenticationAndPreservesEncodedRequestIdentity() {
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(new java.util.UUID(6, 7),
                "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(), null, null);
        when(orders.amendment(42, "a+b")).thenReturn(ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(receipt));
        var response = invoke("trading", "/api/v1/trading/orders/amendments?clientRequestId=a%2Bb", HttpMethod.GET, userHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(orders).amendment(42, "a+b");
        assertThat(invoke("trading", "/api/v1/trading/orders/amendments?userId=43&clientRequestId=x", HttpMethod.GET, userHeaders(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void cancellationLookupBindsTheUserAndKeepsUnknownResultsUncached() {
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(new java.util.UUID(8, 9),
                "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(91L), null, null);
        when(orders.cancellation(42, 91)).thenReturn(ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore()).body(receipt));
        var response = invoke("trading", "/api/v1/trading/orders/cancellations/91", HttpMethod.GET, userHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(orders).cancellation(42, 91);
        assertThat(invoke("trading", "/api/v1/trading/orders/cancellations/91?userId=43", HttpMethod.GET, userHeaders(), null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void committedSubmissionRouteUsesAuthenticatedIdentityAndIsNotAnOrderId() {
        var id = new java.util.UUID(4, 5);
        when(orders.submission(42, "a+b")).thenReturn(ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new com.surprising.trading.api.model.OrderSubmissionResponse(
                        42, ProductLine.SPOT, "a+b", "1", id, "TERMINAL", "INSUFFICIENT_AVAILABLE_BALANCE")));
        var response = invoke("trading", "/api/v1/trading/orders/submission?clientOrderId=a%2Bb", HttpMethod.GET, userHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains("INSUFFICIENT_AVAILABLE_BALANCE", "a+b");
        verify(orders).submission(42, "a+b");
        when(orders.submission(42, "pending")).thenReturn(ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new com.surprising.trading.api.model.OrderSubmissionResponse(
                        42, ProductLine.SPOT, "pending", null, id, "RESULT_UNKNOWN", "RESULT_UNKNOWN")));
        var pending = invoke("trading", "/api/v1/trading/orders/submission?clientOrderId=pending", HttpMethod.GET, userHeaders(), null);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pending.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(invoke("trading", "/api/v1/trading/orders/submission?userId=43&clientOrderId=x", HttpMethod.GET, userHeaders(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void userPriceProtectionRouteReturnsTheProductPolicyWithoutTreatingItAsAnOrderId() {
        when(orders.priceProtection()).thenReturn(new com.surprising.trading.api.model.OrderPriceProtectionResponse(
                ProductLine.LINEAR_PERPETUAL, 12345));
        var response = invoke("trading", "/api/v1/trading/orders/price-protection", HttpMethod.GET, userHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"productLine\":\"LINEAR_PERPETUAL\"", "\"marketMaxSlippagePpm\":12345");
        verify(orders).priceProtection();
        verifyNoMoreInteractions(orders);
    }

    @Test
    void publicBookUnavailabilityKeepsThe503Response() {
        when(market.orderBookSnapshot("1", 30)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_BOOK_UNAVAILABLE"));
        var response = invoke("trading-market", "/api/v1/trading/market/orderbook?instrumentId=1",
                HttpMethod.GET, new HttpHeaders(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("MARKET_BOOK_UNAVAILABLE");
    }

    @Test
    void distinguishesUnknownOutcomeFromNotAcceptedWithoutExposingTransportExceptions() {
        String body = "{\"userId\":42,\"instrumentId\":\"604\",\"marginMode\":\"CROSS\",\"leveragePpm\":1000000}";
        when(leverage.set(any(), any(), any())).thenThrow(new java.util.concurrent.CompletionException(
                new com.surprising.aeron.client.ResultUnknownException(java.util.UUID.randomUUID(), "Aeron internal timeout")));
        var unknown = invoke("trading-leverage", "/api/v1/trading/leverage/settings", HttpMethod.POST, userHeaders(), body);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(new String(unknown.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("RESULT_UNKNOWN", "not yet confirmed").doesNotContain("Aeron");
        doThrow(
                new com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedException(
                        com.surprising.aeron.client.CoreCommandOutcome.notAccepted(-2)))
                .when(leverage).set(any(), any(), any());
        var notAccepted = invoke("trading-leverage", "/api/v1/trading/leverage/settings", HttpMethod.POST, userHeaders(), body);
        assertThat(notAccepted.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new String(notAccepted.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("REQUEST_NOT_ACCEPTED", "was not accepted").doesNotContain("Aeron", "RESULT_UNKNOWN");
    }

    @Test
    void returnsStructuredBusinessRejectionForLeverageWithoutChangingItToNotFound() {
        when(leverage.set(any(), any(), any())).thenThrow(
                new com.surprising.trading.order.service.OrderCommandRejectedException(
                        com.surprising.aeron.protocol.CoreResultCode.LEVERAGE_UPDATE_BLOCKED));
        var result = invoke("trading-leverage", "/api/v1/trading/leverage/settings", HttpMethod.POST,
                userHeaders(), "{\"userId\":42,\"instrumentId\":\"604\",\"marginMode\":\"CROSS\",\"leveragePpm\":1000000}");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new String(result.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"code\":\"LEVERAGE_UPDATE_BLOCKED\"", "Close positions")
                .doesNotContain("Aeron", "INVALID_COMMAND");
    }

    private final com.surprising.websocket.provider.service.SubscriptionRegistry websocket = mock(com.surprising.websocket.provider.service.SubscriptionRegistry.class);

    private final com.surprising.trading.order.service.OrderRequestService orders = mock(com.surprising.trading.order.service.OrderRequestService.class);

    private final com.surprising.trading.order.service.LeverageRequestService leverage = mock(com.surprising.trading.order.service.LeverageRequestService.class);

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
        return new LocalBusinessApi(new TradingLocalRoutes(maintenance, mock(com.surprising.trading.trigger.service.TriggerOrderRequestService.class), mock(com.surprising.trading.trigger.service.AdminTriggerOrderRequestService.class), leverage, mock(com.surprising.trading.order.service.TradingFeeRequestService.class), orders, mock(com.surprising.trading.order.service.AdminOrderRequestService.class), mock(com.surprising.trading.order.service.InstrumentCoreSyncService.class), market), new AccountLocalRoutes(accounts), new InstrumentLocalRoutes(instruments, mock(com.surprising.trading.order.config.OrderBusinessSettingsService.class)), mapper, Validation.buildDefaultValidatorFactory().getValidator(), websocket, accountProperties, tradingProperties);
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
                new com.surprising.gateway.provider.controller.GatewayProxyController(proxy, mock(com.surprising.gateway.provider.product.GatewayProductServices.class))).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/websocket/metrics")
                .header("X-Admin-User-Id", "7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verifyNoInteractions(proxy);

    }

    @Test
    void adminLeverageRequiresTrustedIdentityAndUsesTheExistingLeverageService() {
        String path = "/api/v1/admin/trading/leverage/settings";
        String body = "{\"userId\":900001,\"productLine\":\"LINEAR_PERPETUAL\",\"symbol\":\"BTC-USDT-SWAP\",\"marginMode\":\"CROSS\",\"leveragePpm\":10000000}";
        assertThat(invoke("trading-leverage", path, HttpMethod.POST, userHeaders(), body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(leverage);

        var headers = userHeaders();
        headers.set("X-Admin-User-Id", "7");
        assertThat(invoke("trading-leverage", path, HttpMethod.POST, headers, body).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(leverage).adminSet(eq("7"), any(), eq("LINEAR_PERPETUAL"), isNull());
    }

    @Test
    void publicBookUsesLocalControllerAndDefaultDepthWithoutUserIdentity() {
        assertThat(LocalBusinessApi.isLocalService("trading-market")).isTrue();
        var response = invoke("trading-market", "/api/v1/trading/market/orderbook?instrumentId=1", HttpMethod.GET, new HttpHeaders(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(market).orderBookSnapshot("1", 30);
    }

    @Test
    void adminCanReadMakerLeverageWithoutImpersonatingUser() {
        String path = "/api/v1/admin/trading/leverage/settings?userId=2&instrumentId=604&marginMode=CROSS";
        assertThat(invoke("trading-leverage", path, HttpMethod.GET, userHeaders(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(leverage);
        var headers = userHeaders(); headers.set("X-Admin-User-Id", "7");
        assertThat(invoke("trading-leverage", path, HttpMethod.GET, headers, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(leverage).get(2, "604", com.surprising.trading.api.model.MarginMode.CROSS, "LINEAR_PERPETUAL", null);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unacceptedCommandReturnsUnavailableWithoutRetrying(boolean async) {
        var failure = new com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedException(
                com.surprising.aeron.client.CoreCommandOutcome.notAccepted(io.aeron.Publication.CLOSED));
        if (async) when(orders.cancel(any())).thenReturn(CompletableFuture.failedFuture(failure));
        else when(orders.cancel(any())).thenThrow(failure);
        var response = invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST,
                userHeaders(), "{\"userId\":42,\"orderId\":1}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapper.readTree(response.getBody()).path("message").asText())
                .contains("request was not accepted");
        verify(orders, times(1)).cancel(any());
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
        // Original mappings plus the user order-protection query.
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


}
