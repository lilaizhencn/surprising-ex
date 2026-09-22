package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.controller.AccountController;
import com.surprising.gateway.provider.config.BusinessEndpointConfiguration;
import com.surprising.instrument.provider.controller.InstrumentController;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.controller.*;
import com.surprising.trading.order.service.InstrumentCoreSyncController;
import com.surprising.trading.trigger.controller.*;
import com.surprising.trading.maintenance.AdminMaintenanceController;
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
    private final OrderController orders = mock(OrderController.class);
    private final AccountController accounts = mock(AccountController.class);
    private final InstrumentController instruments = mock(InstrumentController.class);
    private final AdminMaintenanceController maintenance = mock(AdminMaintenanceController.class);
    private final AccountProperties accountProperties = new AccountProperties();
    private final TradingOrderProperties tradingProperties = new TradingOrderProperties();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    {
        accountProperties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        tradingProperties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
    }

    private LocalBusinessApi api() {
        return new LocalBusinessApi(new TradingLocalRoutes(maintenance,
                mock(TriggerOrderController.class), mock(AdminTriggerOrderController.class),
                mock(LeverageController.class), mock(TradingFeeController.class), orders,
                mock(AdminOrderController.class), mock(InstrumentCoreSyncController.class)),
                new AccountLocalRoutes(accounts), new InstrumentLocalRoutes(instruments), mapper,
                Validation.buildDefaultValidatorFactory().getValidator(), accountProperties, tradingProperties);
    }

    private ResponseEntity<byte[]> invoke(String service, String path, HttpMethod method, HttpHeaders headers, String body) {
        return api().invoke(service, URI.create("local:" + path), method, headers,
                body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofMillis(20));
    }

    private HttpHeaders userHeaders() {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", "42");
        headers.set("X-Product-Line", "LINEAR_PERPETUAL");
        return headers;
    }

    @Test
    void accountQueryCallsLocalControllerWithAuthenticatedIdentity() {
        var result = invoke("account", "/api/v1/accounts/balance?asset=USDT", HttpMethod.GET, userHeaders(), null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accounts).balance(42, "USDT");
    }

    @Test
    void forgedQueryAndNestedBatchIdentityNeverReachBusinessCode() {
        assertThat(invoke("account", "/api/v1/accounts/balance?userId=7&asset=USDT", HttpMethod.GET,
                userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invoke("trading", "/api/v1/trading/orders/batch", HttpMethod.POST, userHeaders(),
                "{\"orders\":[{\"userId\":7}]}").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(accounts, orders);
    }

    @Test
    void publicRouteCannotReachAdminControllerByAppendingAdminPath() {
        assertThat(invoke("instrument", "/api/v1/instruments/admin/list", HttpMethod.GET,
                userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invoke("instrument", "/api/v1/instruments/%61dmin/list", HttpMethod.GET,
                userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(instruments);
    }

    @Test
    void malformedBodyAndMissingRequiredParametersAreRejectedBeforeInvocation() {
        assertThat(invoke("trading", "/api/v1/trading/orders", HttpMethod.POST,
                userHeaders(), "{").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invoke("account", "/api/v1/accounts/balance", HttpMethod.GET,
                userHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(accounts, orders);
    }

    @Test
    void specificRouteWinsOverOrderIdPatternAndPreservesQueryDecoding() {
        invoke("trading", "/api/v1/trading/orders/by-client-order-id?userId=42&clientOrderId=a%2Bb", HttpMethod.GET,
                userHeaders(), null);
        verify(orders).getByClientOrderId(42, "a+b", null);
    }

    @Test
    void asyncStatusAndPendingOutcomeArePreserved() {
        when(orders.cancel(any())).thenReturn(CompletableFuture.completedFuture(ResponseEntity.accepted().build()));
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST,
                userHeaders(), "{\"userId\":42,\"orderId\":1}").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var pending = new CompletableFuture<ResponseEntity<com.surprising.trading.api.model.OrderCommandReceipt>>();
        when(orders.cancel(any())).thenReturn(pending);
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST,
                userHeaders(), "{\"userId\":42,\"orderId\":2}").getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(pending.isCancelled()).isFalse();
    }

    @Test
    void failedAsyncCommandKeepsConflictStatus() {
        when(orders.cancel(any())).thenReturn(CompletableFuture.failedFuture(
                new ResponseStatusException(HttpStatus.CONFLICT, "order already completed")));
        assertThat(invoke("trading", "/api/v1/trading/orders/cancel", HttpMethod.POST,
                userHeaders(), "{\"userId\":42,\"orderId\":1}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void conflictingProductSelectorsFailClosedIncludingNestedCommands() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Product-Line", "LINEAR_PERPETUAL");
        request.addParameter("accountType", "SPOT");
        assertThatThrownBy(() -> api().validateProductSelectors(request, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> api().validateProductSelectors(new MockHttpServletRequest(),
                "{\"orders\":[{\"productLine\":\"OPTION\"}]}".getBytes())).isInstanceOf(ResponseStatusException.class);
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
        invoke("trading-orders", "/api/v1/admin/trading/orders/maintenance?productLine=LINEAR_PERPETUAL",
                HttpMethod.GET, headers, null);
        var sequence = inOrder(maintenance);
        sequence.verify(maintenance).authorize("7", "LINEAR_PERPETUAL", ProductLine.LINEAR_PERPETUAL);
        sequence.verify(maintenance).list(0);
    }

    @Test
    void everyPublicBusinessEndpointHasAnExplicitLocalRoute() throws Exception {
        var controllers = java.util.List.of(OrderController.class, AdminOrderController.class,
                TradingFeeController.class, LeverageController.class, TriggerOrderController.class,
                AdminTriggerOrderController.class, AdminMaintenanceController.class,
                InstrumentCoreSyncController.class, AccountController.class, InstrumentController.class);
        var expected = new java.util.HashSet<String>();
        for (Class<?> controller : controllers) {
            var base = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(controller,
                    org.springframework.web.bind.annotation.RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (var method : controller.getDeclaredMethods()) {
                var mapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method,
                        org.springframework.web.bind.annotation.RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                for (String path : paths) {
                    if (!(prefix + path).startsWith("/internal/")) expected.add(prefix + path);
                }
            }
        }
        var actual = new java.util.HashSet<String>();
        for (Class<?> routes : java.util.List.of(TradingLocalRoutes.class, AccountLocalRoutes.class, InstrumentLocalRoutes.class)) {
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
        var handler = new org.springframework.web.method.HandlerMethod(new AccountController(null, null, accountProperties),
                AccountController.class.getMethod("balance", long.class, String.class));
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
