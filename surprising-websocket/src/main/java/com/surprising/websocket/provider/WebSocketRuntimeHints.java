package com.surprising.websocket.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class WebSocketRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        String[] recordTypes = {
                "com.surprising.candlestick.api.model.CandleUpdatedEvent",
                "com.surprising.candlestick.api.model.TradeEvent",
                "com.surprising.trading.api.model.OrderBookDepthEvent",
                "com.surprising.trading.api.model.OrderEvent",
                "com.surprising.trading.api.model.MatchResultEvent",
                "com.surprising.trading.api.model.PublicTradeEvent",
                "com.surprising.account.api.model.PositionUpdatedEvent",
                "com.surprising.risk.api.model.RiskAccountUpdatedEvent",
                "com.surprising.risk.api.model.RiskPositionUpdatedEvent",
                "com.surprising.websocket.api.model.WsClientCommand",
                "com.surprising.websocket.api.model.WsServerMessage"
        };
        for (String recordType : recordTypes) {
            registerRecord(hints, classLoader, recordType);
        }
    }

    private void registerRecord(RuntimeHints hints, ClassLoader classLoader, String className) {
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
