package com.surprising.edge.provider;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.gateway.provider.service.ProductTransferWireRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeRuntimeHintsTest {

    @Test
    void registersWebSocketRecordAccessorsForNativeJsonSerialization() {
        RuntimeHints hints = new RuntimeHints();

        new EdgeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                CandleUpdatedEvent.class,
                TradeEvent.class,
                OrderBookDepthEvent.class,
                OrderBookLevel.class,
                OrderEvent.class,
                TriggerOrderUpdatedEvent.class,
                MatchResultEvent.class,
                MatchTradeEvent.class,
                PublicTradeEvent.class,
                PositionUpdatedEvent.class,
                RiskAccountUpdatedEvent.class,
                RiskPositionUpdatedEvent.class,
                ExecutionReportEvent.class,
                ProductTransferWireRequest.class,
                WsClientCommand.class,
                WsServerMessage.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                    .test(hints)).as("public constructors for %s", type.getName()).isTrue();
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                    .test(hints)).as("public methods for %s", type.getName()).isTrue();
        }
    }
}
