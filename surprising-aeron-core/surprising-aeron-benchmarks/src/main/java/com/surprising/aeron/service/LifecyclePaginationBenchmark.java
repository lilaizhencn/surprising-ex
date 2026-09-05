package com.surprising.aeron.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Real matcher cancellations and account settlement, including cross-Lane cursor boundaries. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class LifecyclePaginationBenchmark {
    @Param({"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    public ProductLine productLine;
    @Param({"256"}) public int maxInFlight;
    private LinearPerpetualBenchmarkSupport.SnapshotTemplate template;
    private LinearPerpetualBenchmarkSupport.Harness harness;
    private String asset;
    private long openingFunds;

    @Setup(Level.Trial)
    public void prepare() {
        if (maxInFlight != 256) throw new IllegalArgumentException("requires 256 in-flight window");
        boolean inverse = productLine == ProductLine.INVERSE_DELIVERY || productLine == ProductLine.INVERSE_PERPETUAL;
        boolean option = productLine == ProductLine.OPTION;
        ContractType type = productLine == ProductLine.INVERSE_PERPETUAL ? ContractType.INVERSE_PERPETUAL
                : productLine == ProductLine.LINEAR_PERPETUAL ? ContractType.LINEAR_PERPETUAL
                : inverse ? ContractType.INVERSE_DELIVERY
                : option ? ContractType.VANILLA_OPTION : ContractType.LINEAR_DELIVERY;
        asset = inverse ? "BTC" : "USDT";
        try (var h = LinearPerpetualBenchmarkSupport.Harness.create(4, productLine)) {
            h.execute(h.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("PAGE", 1,
                            type.ordinal(), "BTC", inverse ? "USD" : "USDT", asset,
                            inverse ? 100 : 1, 1, inverse ? 100 : 1, 100_000, 50_000,
                            0, 0, 2_000_000_000_000L, option ? 0 : -1, option ? 100 : 0))));
            h.execute(h.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(option
                            ? new ApplyMarkPriceCommand("PAGE", 1, 100, 100, 100, 1, h.nextCommandTimestamp())
                            : new ApplyMarkPriceCommand("PAGE", 1, 100, 1, h.nextCommandTimestamp()))));
            h.adjust(999, asset, 1_000_000_000);
            order(h, 999, CoreOrderSide.SELL, 100, 256);
            for (long user = 1000; user < 1256; user++) {
                h.adjust(user, asset, 1_000_000);
                order(h, user, CoreOrderSide.BUY, 100, 1);
                if (!productLine.isFundingProduct()) order(h, user, CoreOrderSide.BUY, 90, 1);
            }
            openingFunds = totalFunds(h.state().tradingState());
            template = h.snapshotTemplate(4);
        }
    }

    private static void order(LinearPerpetualBenchmarkSupport.Harness h, long user,
                              CoreOrderSide side, long price, long quantity) {
        h.execute(h.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, user,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(h.nextOrderId(), "PAGE", 1,
                        side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""))));
    }

    @Setup(Level.Invocation)
    public void restore() {
        harness = LinearPerpetualBenchmarkSupport.Harness.restore(template);
        if (!productLine.isFundingProduct()) harness.advanceClockTo(2_000_000_000_000L);
    }

    @Benchmark
    @OperationsPerInvocation(32)
    public long settle() { return settlePages(false); }

    public long fundingPages(boolean restoreEachPage) {
        long cursor = 0;
        int pages = 0;
        while (true) {
            var response = harness.execute(harness.command(CoreMessageType.APPLY_FUNDING,
                    CommandSource.OPERATIONS, 0, TradingCommandCodec.encodeApplyFunding(
                            new ApplyFundingCommand(10, "PAGE", 1, 100_000, cursor, 16))));
            var progress = CoreFundingProgressCodec.decode(response.data());
            cursor = progress.nextCursorUserId();
            if (++pages > 17) throw new IllegalStateException("funding failed to converge");
            if (pages == 1) {
                if (restoreEachPage) assertFundingPositionFence();
                harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                                "PAGE", 1, 200, 2, harness.nextCommandTimestamp()))));
            }
            if (restoreEachPage) {
                var checkpoint = harness.snapshotTemplate(4);
                harness.close();
                harness = LinearPerpetualBenchmarkSupport.Harness.restore(checkpoint);
            }
            if (progress.complete()) break;
        }
        if (pages != 17) throw new IllegalStateException("unexpected funding page count");
        return 18; // 17 funding commands plus the interleaved mark update.
    }

    private void assertFundingPositionFence() {
        long before = harness.state().tradingState().businessStateHash();
        var response = harness.state().apply(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, 1000,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(harness.nextOrderId(), "PAGE", 1,
                        CoreOrderSide.BUY, 100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""))));
        if (response.status() != ResponseStatus.REJECTED || response.resultCode() != CoreResultCode.LIFECYCLE_IN_PROGRESS
                || harness.state().tradingState().businessStateHash() != before)
            throw new IllegalStateException("funding allowed a position change");
    }

    public long settlePages(boolean restoreEachPage) {
        long orderCursor = 0;
        long userCursor = 0;
        int pages = 0;
        do {
            var result = harness.execute(harness.command(CoreMessageType.SETTLE_INSTRUMENT,
                    CommandSource.OPERATIONS, 0, TradingCommandCodec.encodeSettleInstrument(
                            new SettleInstrumentCommand(10, "PAGE", 1, 120, 0,
                                    userCursor, 16, orderCursor, 16))));
            var progress = CoreSettlementProgressCodec.decode(result.data());
            orderCursor = progress.nextCursorOrderId();
            userCursor = progress.nextCursorUserId();
            if (++pages > 32) throw new IllegalStateException("settlement failed to converge");
            if (restoreEachPage) {
                var checkpoint = harness.snapshotTemplate(4);
                harness.close();
                harness = LinearPerpetualBenchmarkSupport.Harness.restore(checkpoint);
            }
            if (progress.complete()) break;
        } while (true);
        if (pages != 32) throw new IllegalStateException("unexpected business operation count: " + pages);
        return pages;
    }

    @TearDown(Level.Invocation)
    public void verify() {
        try {
            var state = harness.state().tradingState();
            if (totalFunds(state) != openingFunds) throw new IllegalStateException("funds changed");
            for (var order : state.orders().values()) {
                if (order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                    throw new IllegalStateException("settlement left an open order");
            }
            for (var user : state.users().values()) {
                if (productLine.isFundingProduct()) {
                    long expectedFunds = user.userId() == 999 ? 1_000_002_560L : 999_990L;
                    long expectedQuantity = user.userId() == 999 ? -256 : 1;
                    if (user.totalUnits(asset) != expectedFunds
                            || user.positions().get("PAGE").signedQuantitySteps() != expectedQuantity)
                        throw new IllegalStateException("funding price cut or position differs: " + user);
                    continue;
                }
                if (user.reservations().values().stream().anyMatch(r -> r.remainingUnits() != 0)
                        || user.balances().get(asset).lockedUnits() != 0
                        || user.positions().values().stream().anyMatch(p -> p.signedQuantitySteps() != 0))
                    throw new IllegalStateException("settlement left a lock or position: " + user);
            }
            var checkpoint = harness.snapshotTemplate(4);
            try (var restored = LinearPerpetualBenchmarkSupport.Harness.restore(checkpoint)) {
                if (restored.state().tradingState().businessStateHash() != state.businessStateHash())
                    throw new IllegalStateException("snapshot differs");
            }
            if (harness.acceptedMessages() != harness.terminalMessages()
                    || harness.acceptedCoreMessages() != harness.terminalCoreMessages())
                throw new IllegalStateException("unfinished lifecycle commands");
        } finally { harness.close(); }
    }

    private long totalFunds(com.surprising.aeron.service.state.TradingCoreState state) {
        long total = 0;
        for (var user : state.users().values()) total = Math.addExact(total, user.totalUnits(asset));
        var treasury = state.treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        return total;
    }
}
