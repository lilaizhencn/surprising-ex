package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ClusterLifecycleCapacityMain implements AutoCloseable {

    private static final long INITIAL_USER_UNITS = 1_000;
    private static final long LIQUIDATION_USER_UNITS = 180;

    private final ProductLine productLine;
    private final String symbol;
    private final long seed;
    private final int pairs;
    private final AeronClientPool clients;

    private ClusterLifecycleCapacityMain(
            ProductLine productLine,
            List<String> hosts,
            String egress,
            long seed,
            int pairs,
            int connections) {
        if (productLine == ProductLine.SPOT) {
            throw new IllegalArgumentException("SPOT has no derivative lifecycle capacity scenario");
        }
        if (pairs > 128) {
            throw new IllegalArgumentException("lifecycle pairs must be <= 128 for one bounded risk scan");
        }
        this.productLine = productLine;
        this.symbol = "P9-LIFECYCLE-" + productLine.name().replace('_', '-');
        this.seed = seed;
        this.pairs = pairs;
        this.clients = new AeronClientPool(
                "lifecycle-capacity", productLine, hosts, egress, Duration.ofSeconds(10), connections);
    }

    public static void main(String[] args) throws Exception {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "LINEAR_PERPETUAL"));
        List<String> hosts = Arrays.stream(System.getProperty(
                        "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long seed = positiveLong("surprising.aeron.lifecycle-seed", 995001);
        int pairs = positiveInt("surprising.aeron.lifecycle-pairs", 100);
        int connections = positiveInt("surprising.aeron.lifecycle-connections", 8);
        try (ClusterLifecycleCapacityMain capacity = new ClusterLifecycleCapacityMain(
                productLine, hosts, egress, seed, pairs, connections)) {
            capacity.run();
        }
    }

    private void run() {
        setup();
        if (productLine == ProductLine.LINEAR_PERPETUAL
                || productLine == ProductLine.INVERSE_PERPETUAL) {
            liquidationStorm();
        } else {
            lifecycleBatch();
        }
    }

    private void setup() {
        applied(CoreMessageType.UPSERT_INSTRUMENT, 1,
                TradingCommandCodec.encodeUpsertInstrument(instrument()), "instrument");
        for (int pair = 0; pair < pairs; pair++) {
            long shortUser = shortUser(pair);
            long longUser = longUser(pair);
            long funding = fundingUnits();
            adjust(shortUser, funding);
            adjust(longUser, funding);
            long shortReservation = productLine == ProductLine.OPTION ? 1_100 : 100;
            long longReservation = productLine == ProductLine.OPTION ? 1_000 : 100;
            applied(CoreMessageType.PLACE_ORDER, shortUser,
                    order(orderId(pair, 0), CoreOrderSide.SELL, shortReservation), "short-order:" + pair);
            applied(CoreMessageType.PLACE_ORDER, longUser,
                    order(orderId(pair, 1), CoreOrderSide.BUY, longReservation), "long-order:" + pair);
        }
        requireBookEmpty();
    }

    private void liquidationStorm() {
        applied(CoreMessageType.APPLY_MARK_PRICE, 1,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                        symbol, 1, 100, 1, 1_700_000_000_000L)),
                "mark:normal");
        long markPrice = productLine == ProductLine.INVERSE_PERPETUAL ? 25 : 80;
        long started = System.nanoTime();
        applied(CoreMessageType.APPLY_MARK_PRICE, 1,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                        symbol, 1, markPrice, 2, 1_700_000_000_000L)),
                "mark:shock");
        var work = CoreLiquidationWorkCodec.decodeWork(query(
                CoreMessageType.LIQUIDATION_WORK_QUERY, 0, CoreLiquidationWorkCodec.encodeQuery(productLine,
                        com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1_000, 1_048_576)));
        if (work.riskScanPending()) {
            throw new IllegalStateException("risk scan remained pending for lifecycle capacity batch");
        }
        if (work.actions().size() != pairs) {
            throw new IllegalStateException(
                    "liquidation action mismatch expected=" + pairs + " actual=" + work.actions().size());
        }
        List<Long> latencies = new ArrayList<>(pairs);
        for (CoreLiquidationActionView action : work.actions()) {
            long commandStarted = System.nanoTime();
            applied(CoreMessageType.EXECUTE_LIQUIDATION, action.userId(),
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000)),
                    "liquidation:" + action.liquidationId());
            latencies.add(System.nanoTime() - commandStarted);
        }
        long elapsed = System.nanoTime() - started;
        var remaining = CoreLiquidationWorkCodec.decodeWork(query(
                CoreMessageType.LIQUIDATION_WORK_QUERY, 0, CoreLiquidationWorkCodec.encodeQuery(productLine,
                        com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1_000, 1_048_576)));
        if (!remaining.actions().isEmpty() || remaining.riskScanPending()) {
            throw new IllegalStateException("liquidation work remained after storm");
        }
        verifyFundsAndPositions(true);
        Collections.sort(latencies);
        System.out.printf("lifecycleCapacity=PASS scope=LOCAL_CAPACITY productLine=%s "
                        + "scenario=LIQUIDATION_STORM pairs=%d completedLiquidations=%d elapsedMillis=%.3f "
                        + "completedLiquidationsPerSec=%.3f p50Micros=%d p95Micros=%d p99Micros=%d "
                        + "maxMicros=%d fundsDiff=0 bookLevels=0%n",
                productLine, pairs, pairs, elapsed / 1_000_000.0,
                pairs / (elapsed / 1_000_000_000.0), percentileMicros(latencies, 0.50),
                percentileMicros(latencies, 0.95), percentileMicros(latencies, 0.99),
                percentileMicros(latencies, 1.0));
    }

    private void lifecycleBatch() {
        long started = System.nanoTime();
        applied(CoreMessageType.SETTLE_INSTRUMENT, 1,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                        9_500_000_000L + seed, symbol, 1, 120, productLine == ProductLine.OPTION ? 25 : 0)),
                "settle");
        long elapsed = System.nanoTime() - started;
        verifyFundsAndPositions(false);
        long settledPositions = Math.multiplyExact(pairs, 2L);
        System.out.printf("lifecycleCapacity=PASS scope=LOCAL_CAPACITY productLine=%s scenario=%s "
                        + "pairs=%d settledPositions=%d elapsedMillis=%.3f settledPositionsPerSec=%.3f "
                        + "fundsDiff=0 bookLevels=0%n",
                productLine, productLine == ProductLine.OPTION ? "OPTION_EXERCISE_BATCH" : "DELIVERY_BATCH",
                pairs, settledPositions, elapsed / 1_000_000.0,
                settledPositions / (elapsed / 1_000_000_000.0));
    }

    private void verifyFundsAndPositions(boolean liquidation) {
        long actual = 0;
        for (int pair = 0; pair < pairs; pair++) {
            var shortState = userState(shortUser(pair));
            var longState = userState(longUser(pair));
            actual = Math.addExact(actual, balanceTotal(shortState));
            actual = Math.addExact(actual, balanceTotal(longState));
            if (liquidation) {
                requirePosition(longState, 0);
            } else {
                requirePosition(shortState, 0);
                requirePosition(longState, 0);
            }
        }
        for (var treasury : CoreStateQueryCodec.decodeTreasuryState(
                query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]))) {
            if (treasury.asset().equals(settleAsset())) {
                actual = Math.addExact(actual, treasury.feeBalanceUnits());
                actual = Math.addExact(actual, treasury.insuranceBalanceUnits());
            }
        }
        long expected = Math.multiplyExact(Math.multiplyExact(fundingUnits(), pairs), 2L);
        if (actual != expected) {
            throw new IllegalStateException("lifecycle funds mismatch expected=" + expected + " actual=" + actual);
        }
        requireBookEmpty();
    }

    private UpsertInstrumentCommand instrument() {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new UpsertInstrumentCommand(symbol, 1, type.ordinal(), "BTC", "USDT", settleAsset(),
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private byte[] order(long orderId, CoreOrderSide side, long reservedUnits) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, symbol, 1,
                "BTC", "USDT", settleAsset(), side, 100, 10, false,
                ReservationKind.DERIVATIVE_MARGIN, settleAsset(), reservedUnits));
    }

    private void adjust(long userId, long units) {
        applied(CoreMessageType.ADJUST_BALANCE, userId,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset(), units)),
                "fund:" + userId);
    }

    private void applied(CoreMessageType type, long userId, byte[] payload, String id) {
        var response = clients.command(type, stableId(id), userId, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(type + " rejected status=" + response.commandStatus()
                    + " result=" + response.resultCode());
        }
    }

    private byte[] query(CoreMessageType type, long userId, byte[] payload) {
        var response = clients.query(type, UUID.randomUUID(), userId, payload);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(type + " query failed status=" + response.status());
        }
        return response.data();
    }

    private com.surprising.aeron.protocol.CoreUserStateView userState(long userId) {
        return CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, userId, new byte[0]));
    }

    private long balanceTotal(com.surprising.aeron.protocol.CoreUserStateView state) {
        return state.balances().stream().filter(value -> value.asset().equals(settleAsset()))
                .mapToLong(value -> Math.addExact(value.availableUnits(), value.lockedUnits())).sum();
    }

    private void requireBookEmpty() {
        var book = OrderBookBootstrapLoader.load((type, payload) -> query(type, 0, payload));
        if (!book.levels().isEmpty()) {
            throw new IllegalStateException("lifecycle book is not empty levels=" + book.levels().size());
        }
    }

    private void requirePosition(com.surprising.aeron.protocol.CoreUserStateView state, long expected) {
        long actual = state.positions().stream().filter(value -> value.symbol().equals(symbol))
                .mapToLong(value -> value.signedQuantitySteps()).sum();
        if (actual != expected) {
            throw new IllegalStateException("position mismatch user=" + state.userId()
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    private long fundingUnits() {
        if (productLine == ProductLine.OPTION) {
            return 2_000;
        }
        return productLine == ProductLine.LINEAR_PERPETUAL
                || productLine == ProductLine.INVERSE_PERPETUAL ? LIQUIDATION_USER_UNITS : INITIAL_USER_UNITS;
    }

    private String settleAsset() {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    private long shortUser(int pair) {
        return 60_000_000_000L + seed * 1_000L + pair * 2L;
    }

    private long longUser(int pair) {
        return shortUser(pair) + 1;
    }

    private long orderId(int pair, int side) {
        return 70_000_000_000L + seed * 1_000L + pair * 2L + side;
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes((seed + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static long percentileMicros(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return TimeUnit.NANOSECONDS.toMicros(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private static int positiveInt(String name, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long positiveLong(String name, long defaultValue) {
        long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public void close() {
        clients.close();
    }
}
