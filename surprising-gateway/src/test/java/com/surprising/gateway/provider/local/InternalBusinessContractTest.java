package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.account.provider.controller.AccountController;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.order.controller.TradingController;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

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
                "com.surprising.trading.matching.controller.MarketDataInternalController")) {
            assertThat(org.springframework.util.ClassUtils.isPresent(name, getClass().getClassLoader()))
                    .as("duplicate public entry %s", name).isFalse();
        }
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
