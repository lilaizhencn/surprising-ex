package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.provider.controller.AccountController;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountQueryService;
import com.surprising.account.provider.service.AccountService;
import com.surprising.instrument.provider.controller.InstrumentController;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.controller.AdminTradingController;
import com.surprising.trading.order.controller.OrderBusinessSettingsController;
import com.surprising.trading.order.controller.TradingController;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.service.OrderBusinessSettingsService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.TradingFeeService;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.maintenance.service.MaintenanceService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class InternalBusinessContractTest {
    @Test
    void independentMakerFeignContractsStillHaveInternalHttpEndpoints() {
        Map.of(AccountRpcApi.class, AccountController.class,
                OrderRpcApi.class, TradingController.class,
                MarketDataRpcApi.class, TradingController.class).forEach((api, controller) -> {
                    String prefix = api.getAnnotation(FeignClient.class).path();
                    assertThat(routes(controller, "")).as("%s internal contract", api.getSimpleName())
                            .containsAll(routes(api, prefix));
                });
    }

    @Test
    void duplicatePublicControllerClassesAreRemoved() {
        for (String name : java.util.List.of(
                "com.surprising.instrument.provider.controller.InstrumentMvcController",
                "com.surprising.trading.order.controller.TradingMvcController",
                "com.surprising.trading.order.controller.AdminTradingMvcController",
                "com.surprising.account.provider.controller.AccountMvcController",
                "com.surprising.trading.order.controller.OrderBusinessSettingsMvcController",
                "com.surprising.trading.order.controller.AdminOrderController",
                "com.surprising.trading.order.controller.TradingFeeController",
                "com.surprising.trading.order.controller.LeverageController",
                "com.surprising.trading.trigger.controller.TriggerOrderController",
                "com.surprising.trading.trigger.controller.AdminTriggerOrderController",
                "com.surprising.trading.maintenance.AdminMaintenanceController",
                "com.surprising.trading.order.service.InstrumentCoreSyncController",
                "com.surprising.websocket.provider.service.AdminWebSocketMetricsController",
                "com.surprising.trading.order.controller.OrderInternalController",
                "com.surprising.account.provider.controller.AccountInternalController",
                "com.surprising.trading.matching.controller.MarketDataInternalController",
                "com.surprising.gateway.provider.controller.GatewayProxyController",
                "com.surprising.gateway.provider.product.GatewayLocalDispatcher")) {
            assertThat(org.springframework.util.ClassUtils.isPresent(name, getClass().getClassLoader()))
                    .as("duplicate public entry %s", name).isFalse();
        }
    }

    @Test
    void localControllersDependOnHandlersNotOtherRestControllers() {
        for (Class<?> entry : java.util.List.of(TradingController.class, AdminTradingController.class,
                AccountController.class, InstrumentController.class, OrderBusinessSettingsController.class)) {
            for (var field : entry.getDeclaredFields()) {
                assertThat(field.getType().isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestController.class))
                        .as("%s.%s must depend on a handler/service", entry.getSimpleName(), field.getName()).isFalse();
            }
        }
    }

    @Test
    void everyPublicBusinessEndpointHasAnExplicitMvcRoute() throws Exception {
        var expected = java.nio.file.Files.readAllLines(java.nio.file.Path.of("src/test/resources/local-business-route-paths.txt"));
        var context = new StaticApplicationContext();
        context.registerBean(TradingController.class, () -> new TradingController(
                mock(OrderService.class),
                mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class),
                mock(TriggerOrderService.class),
                mock(LeverageService.class),
                mock(TradingFeeService.class),
                mock(MatchingMarketDataService.class)));
        context.registerBean(AdminTradingController.class, () -> new AdminTradingController(
                mock(OrderService.class),
                mock(TriggerOrderService.class),
                mock(MaintenanceService.class),
                mock(LeverageService.class),
                mock(TradingFeeService.class),
                mock(InstrumentCoreSyncService.class)));
        context.registerBean(AccountController.class, () -> new AccountController(
                mock(AccountService.class),
                mock(AccountQueryService.class),
                mock(AccountCommandGateway.class)));
        context.registerBean(OrderBusinessSettingsController.class, () -> new OrderBusinessSettingsController(
                mock(OrderBusinessSettingsService.class), mock(tools.jackson.databind.ObjectMapper.class)));
        context.registerBean(InstrumentController.class, () -> new InstrumentController(
                mock(InstrumentService.class), mock(tools.jackson.databind.ObjectMapper.class)));
        context.refresh();
        var mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        var actual = new java.util.HashSet<String>();
        mapping.getHandlerMethods().keySet().forEach(info -> actual.addAll(info.getPatternValues()));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private Set<String> routes(Class<?> type, String prefix) {
        Set<String> routes = new HashSet<>();
        for (var method : type.getMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
            for (var verb : mapping.method()) {
                for (String path : paths) routes.add(verb.name() + " " + prefix + path);
            }
        }
        return routes;
    }
}
