package com.surprising.edge.provider;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.gateway.provider.service.ProductTransferWireRequest;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.websocket.api.model.ExecutionReportEvent;
import com.surprising.websocket.api.model.WsClientCommand;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.service.KafkaFanoutConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class EdgeRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        registerRecord(hints, CandleUpdatedEvent.class);
        registerRecord(hints, TradeEvent.class);
        registerRecord(hints, OrderBookDepthEvent.class);
        registerRecord(hints, OrderBookLevel.class);
        registerRecord(hints, OrderEvent.class);
        registerRecord(hints, TriggerOrderUpdatedEvent.class);
        registerRecord(hints, MatchResultEvent.class);
        registerRecord(hints, MatchTradeEvent.class);
        registerRecord(hints, PublicTradeEvent.class);
        registerRecord(hints, PositionUpdatedEvent.class);
        registerRecord(hints, RiskAccountUpdatedEvent.class);
        registerRecord(hints, RiskPositionUpdatedEvent.class);
        registerRecord(hints, ExecutionReportEvent.class);
        registerRecord(hints, ProductTransferWireRequest.class);
        registerRecord(hints, WsClientCommand.class);
        registerRecord(hints, WsServerMessage.class);
        hints.reflection().registerType(KafkaFanoutConsumer.class, MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
