package com.surprising.gateway.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class GatewayRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        String[] reflectionTypes = {
                "com.surprising.gateway.provider.service.ProductTransferWireRequest",
                "com.surprising.candlestick.api.model.CandleUpdatedEvent",
                "com.surprising.candlestick.api.model.TradeEvent",
                "com.surprising.trading.api.model.OrderBookDepthEvent",
                "com.surprising.trading.api.model.OrderEvent",
                "com.surprising.trading.api.model.PublicTradeEvent",
                "com.surprising.account.api.model.PositionUpdatedEvent",
                "com.surprising.risk.api.model.RiskAccountUpdatedEvent",
                "com.surprising.risk.api.model.RiskPositionUpdatedEvent",
                "com.surprising.price.api.model.IndexComponentSnapshot",
                "com.surprising.price.api.model.IndexPriceEvent",
                "com.surprising.price.api.model.MarkPriceEvent",
                "com.surprising.price.api.model.MarkPricePublishedEvent",
                "com.surprising.price.api.model.PriceEventType",
                "com.surprising.price.api.model.PricePublishedEvent",
                "com.surprising.websocket.api.model.WsClientCommand",
                "com.surprising.websocket.api.model.WsServerMessage"
        };
        for (String reflectionType : reflectionTypes) {
            registerType(hints, classLoader, reflectionType);
        }
    }

    private void registerType(RuntimeHints hints, ClassLoader classLoader, String className) {
        try {
            hints.reflection().registerType(Class.forName(className, false, classLoader),
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Native runtime hint type not found: " + className, ex);
        }
    }
}
