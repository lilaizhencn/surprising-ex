package com.surprising.gateway.provider.service;


import com.surprising.gateway.provider.controller.RuntimeController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.repository.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.ValkeyUserQueries;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.TradeProjectionRepository;
import com.surprising.trading.order.service.TradingMetricsService;
import com.surprising.trading.order.service.OrderAeronGateway;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.OrderValidator;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;
import com.surprising.trading.trigger.repository.TriggerOrderProjectionRepository;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ConstructorInjectionContextTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void productQueriesAndLocalProxyUseTheConstructorInjectedViews(ProductLine product) {
        try (var context = new AnnotationConfigApplicationContext()) {
            var orderProperties = new TradingOrderProperties();
            orderProperties.getKafka().setProductLine(product);
            context.registerBean(TradingOrderProperties.class, () -> orderProperties);
            var triggerProperties = new TriggerProperties();
            triggerProperties.setProductLine(product);
            context.registerBean(TriggerProperties.class, () -> triggerProperties);
            var gatewayProperties = new GatewayProperties();
            gatewayProperties.setRoutes(Map.of("trading-market", new GatewayProperties.BackendRoute(
                    "local:", "/api/v1/trading/market", false)));
            context.registerBean(GatewayProperties.class, () -> gatewayProperties);
            var products = mock(GatewayProductServices.class);
            when(products.enabled()).thenReturn(List.of(product));
            context.registerBean(GatewayProductServices.class, () -> products);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            for (Class<?> type : List.of(AuthService.class, GatewayApiKeyService.class, AdminAuditRepository.class, AdminApprovalRepository.class,
                    ProductTransferService.class, ProductTransferSecurityService.class, RestClient.class,
                    com.surprising.instrument.provider.service.InstrumentService.class,
                    OrderValidator.class, OrderAeronGateway.class,
                    AeronOrderProjectionRepository.class, MatchingMarketDataService.class, TradingMetricsService.class,
                    TradeProjectionRepository.class, ValkeyUserQueries.class, TriggerOrderAeronGateway.class,
                    TriggerOrderProjectionRepository.class)) {
                registerMock(context, type);
            }
            context.register(com.surprising.gateway.provider.auth.service.AdminApprovalService.class, com.surprising.gateway.provider.controller.RuntimeController.class, GatewayProductSelection.class, OrderService.class, TriggerOrderService.class);
            context.refresh();

            var metrics = context.getBean(TradingMetricsService.class);
            var snapshot = new ObjectMapper().createObjectNode().put("productLine", product.name());
            when(metrics.require(product, 60, 20)).thenReturn(snapshot);
            assertThat(context.getBean(OrderService.class).tradingMetrics(60, 20, product)).isSameAs(snapshot);

            var projection = context.getBean(TriggerOrderProjectionRepository.class);
            when(projection.get(product, 1001L, 99L)).thenReturn(java.util.Optional.empty());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(TriggerOrderService.class).get(1001L, 99L))
                    .isInstanceOf(IllegalStateException.class);
            verify(projection).get(product, 1001L, 99L);
            verifyNoInteractions(context.getBean(TriggerOrderAeronGateway.class), context.getBean(OrderAeronGateway.class));

            assertThat(context.getBean(com.surprising.gateway.provider.controller.RuntimeController.class).runtime().get("productLines"))
                    .containsExactly(product);
            verifyNoInteractions(context.getBean(RestClient.class));
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
