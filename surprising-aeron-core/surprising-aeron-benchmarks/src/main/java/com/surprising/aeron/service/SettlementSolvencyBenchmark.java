package com.surprising.aeron.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SettlementSolvencyBenchmark {
    @Param({"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"}) public ProductLine productLine;
    @Param({"CROSS", "ISOLATED"}) public CoreMarginMode marginMode;
    @Param({"256"}) public int maxInFlight;
    private LinearPerpetualBenchmarkSupport.SnapshotTemplate template;
    private LinearPerpetualBenchmarkSupport.Harness harness;
    private String asset;
    private long deficit;
    private long makerOpening;
    private long loss;

    @Setup(Level.Trial)
    public void prepare() {
        if (maxInFlight != 256) throw new IllegalArgumentException("requires 256 in-flight window");
        boolean inverse = productLine == ProductLine.INVERSE_DELIVERY;
        boolean option = productLine == ProductLine.OPTION;
        asset = inverse ? "BTC" : "USDT";
        loss = inverse ? 90 : 900;
        ContractType type = inverse ? ContractType.INVERSE_DELIVERY
                : option ? ContractType.VANILLA_OPTION : ContractType.LINEAR_DELIVERY;
        try (var h = LinearPerpetualBenchmarkSupport.Harness.create(4, productLine)) {
            h.execute(h.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("DEBT", 1,
                            type.ordinal(), "BTC", inverse ? "USD" : "USDT", asset, inverse ? 100 : 1,
                            1, inverse ? 100 : 1, 100_000, 50_000, 0, 0,
                            2_000_000_000_000L, option ? 0 : -1, option ? 100 : 0))));
            h.execute(h.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(option
                            ? new ApplyMarkPriceCommand("DEBT", 1, 100, 100, 100, 1, h.nextCommandTimestamp())
                            : new ApplyMarkPriceCommand("DEBT", 1, 100, 1, h.nextCommandTimestamp()))));
            h.adjust(999, asset, 1_000_000_000L);
            order(h, 999, CoreOrderSide.BUY, 256, CoreMarginMode.CROSS);
            for (long user = 1000; user < 1256; user++) {
                h.adjust(user, asset, 1_000_000);
                order(h, user, CoreOrderSide.SELL, 1, marginMode);
                long available = h.state().tradingState().user(user).balances().get(asset).availableUnits();
                h.adjust(user, asset, -available);
            }
            var state = h.state().tradingState();
            deficit = loss - state.user(1000).positions().get("DEBT").positionMarginUnits();
            if (deficit <= 0) throw new IllegalStateException("fixture must be insolvent");
            makerOpening = state.user(999).totalUnits(asset);
            template = h.snapshotTemplate(4);
        }
    }

    private static void order(LinearPerpetualBenchmarkSupport.Harness h, long user, CoreOrderSide side,
                              long quantity, CoreMarginMode margin) {
        h.execute(h.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, user,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(h.nextOrderId(), "DEBT", 1,
                        side, 100, quantity, false, margin, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, ""))));
    }

    @Setup(Level.Invocation)
    public void restore() {
        harness = LinearPerpetualBenchmarkSupport.Harness.restore(template);
        harness.advanceClockTo(2_000_000_000_000L);
    }

    private CoreSettlementProgressView page(long cursor) {
        return CoreSettlementProgressCodec.decode(harness.execute(harness.command(CoreMessageType.SETTLE_INSTRUMENT,
                CommandSource.OPERATIONS, 0, TradingCommandCodec.encodeSettleInstrument(
                        new SettleInstrumentCommand(11, "DEBT", 1, 1000, 0, cursor, 16, 0, 16)))).data());
    }

    @Benchmark
    @OperationsPerInvocation(19)
    public long pauseRefillResume() { return run(false); }

    public long run(boolean recover) {
        var paused = page(0);
        if (paused.complete() || paused.processedUsers() != 0 || paused.nextCursorUserId() != 0
                || paused.requiredInsuranceUnits() != deficit * 15)
            throw new IllegalStateException("unfunded page did not pause atomically");
        if (recover && (harness.state().tradingState().user(999).totalUnits(asset) != makerOpening
                || harness.state().tradingState().user(1000).positions().get("DEBT").signedQuantitySteps() != -1))
            throw new IllegalStateException("unfunded page mutated balances or positions");
        long partialInsurance = 0;
        if (recover) {
            partialInsurance = deficit * 15 - 1;
            harness.execute(harness.command(CoreMessageType.ADJUST_INSURANCE_FUND, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(new AdjustInsuranceFundCommand(asset, partialInsurance))));
            if (page(0).requiredInsuranceUnits() != deficit * 15
                    || harness.state().tradingState().treasuryState().insuranceBalances().get(asset) != partialInsurance
                    || harness.state().tradingState().user(999).totalUnits(asset) != makerOpening)
                throw new IllegalStateException("partially funded page consumed cash");
            var checkpoint = harness.snapshotTemplate(4);
            harness.close();
            harness = LinearPerpetualBenchmarkSupport.Harness.restore(checkpoint);
            if (harness.state().tradingState().treasuryState().lifecycleProgress("DEBT").requiredInsuranceUnits()
                    != deficit * 15) throw new IllegalStateException("lost settlement debt on recovery");
            if (page(0).requiredInsuranceUnits() != deficit * 15)
                throw new IllegalStateException("retry changed required insurance");
        }
        harness.execute(harness.command(CoreMessageType.ADJUST_INSURANCE_FUND, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeAdjustInsuranceFund(new AdjustInsuranceFundCommand(asset, deficit * 256 - partialInsurance))));
        long cursor = 0;
        for (int pages = 1; pages <= 17; pages++) {
            var progress = page(cursor);
            if (progress.requiredInsuranceUnits() != 0) throw new IllegalStateException("funded page paused");
            if (progress.complete()) {
                if (pages != 17) throw new IllegalStateException("wrong settlement count");
                if (recover) {
                    long completedHash = harness.state().tradingState().businessStateHash();
                    if (!page(0).complete() || harness.state().tradingState().businessStateHash() != completedHash)
                        throw new IllegalStateException("completed settlement paid twice");
                }
                return 19;
            }
            cursor = progress.nextCursorUserId();
        }
        throw new IllegalStateException("settlement did not converge");
    }

    @TearDown(Level.Invocation)
    public void verify() {
        try {
            var state = harness.state().tradingState();
            long total = 0;
            for (var user : state.users().values()) {
                if (user.balances().get(asset).lockedUnits() != 0
                        || user.positions().values().stream().anyMatch(p -> p.signedQuantitySteps() != 0))
                    throw new IllegalStateException("non-terminal settlement state");
                long expected = user.userId() == 999 ? makerOpening + loss * 256 : 0;
                if (user.totalUnits(asset) != expected) throw new IllegalStateException("wrong settlement cash: " + user);
                total = Math.addExact(total, user.totalUnits(asset));
            }
            if (total != makerOpening + loss * 256 || !state.treasuryState().insuranceBalances().isEmpty()
                    || !state.treasuryState().clearingPnlBalances().isEmpty()
                    || !state.treasuryState().lifecycleProgress().isEmpty())
                throw new IllegalStateException("insurance or clearing failed conservation");
            var checkpoint = harness.snapshotTemplate(4);
            try (var restored = LinearPerpetualBenchmarkSupport.Harness.restore(checkpoint)) {
                if (restored.state().tradingState().businessStateHash() != state.businessStateHash())
                    throw new IllegalStateException("recovered settlement differs");
            }
        } finally { harness.close(); }
    }
}
