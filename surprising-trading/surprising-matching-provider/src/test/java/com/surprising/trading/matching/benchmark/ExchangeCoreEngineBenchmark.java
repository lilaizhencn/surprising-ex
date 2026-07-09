package com.surprising.trading.matching.benchmark;

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
import com.surprising.trading.matching.service.ExchangeCoreEngine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class ExchangeCoreEngineBenchmark {

    private static final String SYMBOL = "BTC-USDT";
    private static final long PRICE_TICKS = 600_000L;
    private static final long MAKER_USER_ID = 10_001L;
    private static final long TAKER_USER_ID = 20_002L;
    private static final MatchingSymbol MATCHING_SYMBOL = new MatchingSymbol(SYMBOL, 301, 11, 12);
    private static final InstrumentSymbol INSTRUMENT_SYMBOL = new InstrumentSymbol(SYMBOL, "BTC", "USDT", "USDT");

    private ExchangeCoreEngineBenchmark() {
    }

    public static void main(String[] args) {
        int orders = intArg(args, 0, "BENCHMARK_ORDERS", 100_000);
        int warmupOrders = intArg(args, 1, "BENCHMARK_WARMUP_ORDERS", 10_000);
        if (orders <= 0 || warmupOrders < 0) {
            throw new IllegalArgumentException("orders must be positive and warmupOrders must be >= 0");
        }

        if (warmupOrders > 0) {
            runOnce(warmupOrders, false);
        }
        BenchmarkResult result = runOnce(orders, true);
        System.out.printf(
                "orders=%d trades=%d maker_setup_ms=%.3f trade_submit_ms=%.3f orders_per_sec=%.2f trades_per_sec=%.2f%n",
                result.orders(), result.trades(), result.makerSetupMs(), result.tradeSubmitMs(),
                result.ordersPerSecond(), result.tradesPerSecond());
    }

    private static BenchmarkResult runOnce(int orders, boolean report) {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-benchmark-" + System.nanoTime());
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FixedMatchingSymbolRepository(), new EmptyRecoveryRepository());

        try {
            engine.start();
            long makerStart = System.nanoTime();
            submitSuccessful(engine, place(1L, 1L, MAKER_USER_ID, OrderSide.SELL,
                    TimeInForce.GTC, PRICE_TICKS, orders + 1L));
            submitSuccessful(engine, place(2L, 2L, TAKER_USER_ID, OrderSide.BUY,
                    TimeInForce.IOC, PRICE_TICKS - 1L, 1L));
            long makerEnd = System.nanoTime();

            AtomicLong trades = new AtomicLong();
            long tradeStart = System.nanoTime();
            for (int i = 0; i < orders; i++) {
                OrderCommand response = submitSuccessful(engine, place(3L + i, 3L + i,
                        TAKER_USER_ID, OrderSide.BUY, TimeInForce.IOC, PRICE_TICKS, 1L));
                response.processMatcherEvents(event -> {
                    if (event.eventType == MatcherEventType.TRADE) {
                        trades.incrementAndGet();
                    }
                });
            }
            long tradeEnd = System.nanoTime();

            if (trades.get() != orders) {
                throw new IllegalStateException("expected " + orders + " trades, got " + trades.get());
            }
            double makerSetupMs = elapsedMs(makerStart, makerEnd);
            double tradeSubmitMs = elapsedMs(tradeStart, tradeEnd);
            BenchmarkResult result = new BenchmarkResult(orders, trades.get(), makerSetupMs, tradeSubmitMs);
            if (!report && result.ordersPerSecond() <= 0) {
                throw new IllegalStateException("benchmark did not run");
            }
            return result;
        } finally {
            engine.stop();
        }
    }

    private static OrderCommand submitSuccessful(ExchangeCoreEngine engine, OrderCommandEvent command) {
        OrderCommand response = engine.submit(command, MATCHING_SYMBOL, command.priceTicks());
        if (response.resultCode != CommandResultCode.SUCCESS) {
            throw new IllegalStateException("commandId=" + command.commandId() + " failed: " + response.resultCode);
        }
        return response;
    }

    private static OrderCommandEvent place(long commandId,
                                           long orderId,
                                           long userId,
                                           OrderSide side,
                                           TimeInForce timeInForce,
                                           long priceTicks,
                                           long quantitySteps) {
        return new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId,
                "benchmark-" + orderId, SYMBOL, 1L, side, OrderType.LIMIT, timeInForce,
                priceTicks, quantitySteps, 2L, 5L, false, false, Instant.now(), "benchmark-" + commandId);
    }

    private static int intArg(String[] args, int index, String envName, int defaultValue) {
        if (args.length > index && !args[index].isBlank()) {
            return Integer.parseInt(args[index]);
        }
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static double elapsedMs(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000.0;
    }

    private record BenchmarkResult(long orders, long trades, double makerSetupMs, double tradeSubmitMs) {

        double ordersPerSecond() {
            return orders / (tradeSubmitMs / 1000.0);
        }

        double tradesPerSecond() {
            return trades / (tradeSubmitMs / 1000.0);
        }
    }

    private static final class FixedMatchingSymbolRepository extends MatchingSymbolRepository {

        private FixedMatchingSymbolRepository() {
            super(null, null);
        }

        @Override
        public List<InstrumentSymbol> currentTradingSymbols() {
            return List.of(INSTRUMENT_SYMBOL);
        }

        @Override
        public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
            return SYMBOL.equals(symbol) ? Optional.of(INSTRUMENT_SYMBOL) : Optional.empty();
        }

        @Override
        public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
            return MATCHING_SYMBOL;
        }
    }

    private static final class EmptyRecoveryRepository extends MatchingOrderBookRecoveryRepository {

        private EmptyRecoveryRepository() {
            super(null);
        }

        @Override
        public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant lastCreatedAt,
                                                                        long lastOrderId,
                                                                        int limit) {
            return List.of();
        }
    }
}
