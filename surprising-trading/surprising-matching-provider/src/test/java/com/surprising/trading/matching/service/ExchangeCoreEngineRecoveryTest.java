package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import com.surprising.trading.matching.repository.MatchingOrderBookRecoveryRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExchangeCoreEngineRecoveryTest {

    @Test
    void restoresOpenLimitOrderIntoExchangeCoreBook() {
        MatchingSymbol symbol = new MatchingSymbol("BTC-USDT", 101, 11, 12);
        ExchangeCoreEngine engine = engineWithRecoveredOrders(symbol, List.of(
                new RecoveredOrderBookOrder(1L, 1001L, "BTC-USDT", OrderSide.SELL,
                        TimeInForce.GTC, 100L, 10L, Instant.parse("2026-01-01T00:00:00Z"))));
        try {
            engine.start();

            OrderCommandEvent taker = new OrderCommandEvent(OrderCommandType.PLACE, 2L, 2L, 2002L,
                    null, "BTC-USDT", 1L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.now());
            var response = engine.submit(taker, symbol, 100L);

            AtomicInteger trades = new AtomicInteger();
            response.processMatcherEvents(event -> {
                assertThat(event.eventType).isEqualTo(MatcherEventType.TRADE);
                assertThat(event.matchedOrderId).isEqualTo(1L);
                assertThat(event.price).isEqualTo(100L);
                assertThat(event.size).isEqualTo(3L);
                trades.incrementAndGet();
            });
            assertThat(response.resultCode).isEqualTo(CommandResultCode.SUCCESS);
            assertThat(trades).hasValue(1);
        } finally {
            engine.stop();
        }
    }

    @Test
    void rejectsRecoveryWhenPersistedOpenOrdersWouldCross() {
        MatchingSymbol symbol = new MatchingSymbol("BTC-USDT", 102, 11, 12);
        ExchangeCoreEngine engine = engineWithRecoveredOrders(symbol, List.of(
                new RecoveredOrderBookOrder(1L, 1001L, "BTC-USDT", OrderSide.SELL,
                        TimeInForce.GTC, 100L, 10L, Instant.parse("2026-01-01T00:00:00Z")),
                new RecoveredOrderBookOrder(2L, 2002L, "BTC-USDT", OrderSide.BUY,
                        TimeInForce.GTC, 100L, 1L, Instant.parse("2026-01-01T00:00:01Z"))));
        try {
            assertThatThrownBy(engine::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("crossed the book");
        } finally {
            engine.stop();
        }
    }

    private ExchangeCoreEngine engineWithRecoveredOrders(MatchingSymbol matchingSymbol,
                                                         List<RecoveredOrderBookOrder> recoveredOrders) {
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("recovery-test-" + matchingSymbol.symbolId());
        return new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository(recoveredOrders));
    }

    private static class FakeMatchingSymbolRepository extends MatchingSymbolRepository {
        private final InstrumentSymbol instrument;
        private final MatchingSymbol matchingSymbol;

        FakeMatchingSymbolRepository(InstrumentSymbol instrument, MatchingSymbol matchingSymbol) {
            super(null, null);
            this.instrument = instrument;
            this.matchingSymbol = matchingSymbol;
        }

        @Override
        public List<InstrumentSymbol> currentTradingSymbols() {
            return List.of(instrument);
        }

        @Override
        public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
            return instrument.symbol().equals(symbol) ? Optional.of(instrument) : Optional.empty();
        }

        @Override
        public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
            return matchingSymbol;
        }
    }

    private static class FakeRecoveryRepository extends MatchingOrderBookRecoveryRepository {
        private final List<RecoveredOrderBookOrder> recoveredOrders;

        FakeRecoveryRepository(List<RecoveredOrderBookOrder> recoveredOrders) {
            super(null);
            this.recoveredOrders = recoveredOrders;
        }

        @Override
        public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant lastCreatedAt,
                                                                        long lastOrderId,
                                                                        int limit) {
            return recoveredOrders;
        }
    }
}
