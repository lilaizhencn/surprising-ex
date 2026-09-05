package com.surprising.aeron.service;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Real Core risk commands over funded, matched accounts; query work is not counted as business ops. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
@Threads(1)
public class DerivativeRiskBoundaryBenchmark {
    @Benchmark
    public long riskAndAdl(BoundaryState state, Counters counters) {
        var h = state.harness;
        long accepted = h.acceptedMessages();
        long terminal = h.terminalMessages();
        long core = h.acceptedCoreMessages();
        long terminalCore = h.terminalCoreMessages();
        for (int i = 0; i < state.maxInFlight; i++) {
            String symbol = (i & 1) == 0 ? "RISK-LONG" : "RISK-SHORT";
            state.mark(symbol, symbol.equals("RISK-LONG") ? 120 : 300);
        }
        state.drainRisk();
        if (state.productLine != ProductLine.OPTION) {
            state.queryCandidates();
            counters.queries++;
        }
        counters.acceptedBusinessOperations += h.acceptedMessages() - accepted;
        counters.terminalBusinessOperations += h.terminalMessages() - terminal;
        counters.acceptedCoreMessages += h.acceptedCoreMessages() - core;
        counters.terminalCoreMessages += h.terminalCoreMessages() - terminalCore;
        counters.unfinishedBusinessOperations += h.acceptedMessages() - h.terminalMessages();
        counters.unfinishedCoreMessages += h.acceptedCoreMessages() - h.terminalCoreMessages();
        return h.terminalMessages();
    }

    @State(Scope.Thread)
    public static class BoundaryState {
        @Param({"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
        public ProductLine productLine;
        @Param("256") public int maxInFlight;
        @Param("4") public int accountLanes;
        private LinearPerpetualBenchmarkSupport.Harness harness;
        private String asset;
        private long sequence = 1;
        private long openingFunds;

        @Setup(Level.Trial)
        public void setup() {
            if (maxInFlight != 256) throw new IllegalArgumentException("requires 256 in-flight window");
            harness = LinearPerpetualBenchmarkSupport.Harness.create(accountLanes, productLine);
            ContractType type = switch (productLine) {
                case LINEAR_DELIVERY -> ContractType.LINEAR_DELIVERY;
                case INVERSE_DELIVERY -> ContractType.INVERSE_DELIVERY;
                case OPTION -> ContractType.VANILLA_OPTION;
                default -> throw new IllegalArgumentException("unsupported boundary fixture");
            };
            asset = type.isInverse() ? "BTC" : "USDT";
            for (String symbol : new String[]{"RISK-LONG", "RISK-SHORT"}) {
                var instrument = new UpsertInstrumentCommand(symbol, 1, type.ordinal(), "BTC",
                        type.isInverse() ? "USD" : "USDT", asset, type.isInverse() ? 100 : 1,
                        1, type.isInverse() ? 100 : 1, 100_000, 50_000, 0, 0,
                        2_000_000_000_000L, type.isOption() ? OptionType.CALL.ordinal() : -1,
                        type.isOption() ? 100 : 0);
                harness.execute(harness.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS,
                        0, TradingCommandCodec.encodeUpsertInstrument(instrument)));
                mark(symbol, 100);
            }
            harness.adjust(999, asset, 1_000_000_000L);
            order(999, "RISK-LONG", CoreOrderSide.SELL, 256, CoreTimeInForce.GTC);
            order(999, "RISK-SHORT", CoreOrderSide.BUY, 2560, CoreTimeInForce.GTC);
            for (long user = 1000; user < 1256; user++) {
                harness.adjust(user, asset, 10_000);
                order(user, "RISK-LONG", CoreOrderSide.BUY, 1, CoreTimeInForce.IOC);
                order(user, "RISK-SHORT", CoreOrderSide.SELL, 10, CoreTimeInForce.IOC);
                long available = harness.state().tradingState().user(user).balances().get(asset).availableUnits();
                if (available > 0) harness.adjust(user, asset, -available);
            }
            // Keep maker liquidity present throughout the measurement window.
            order(999, "RISK-LONG", CoreOrderSide.SELL, 1, CoreTimeInForce.GTC);
            openingFunds = funds(harness.state().tradingState());
        }

        private void order(long user, String symbol, CoreOrderSide side, long quantity, CoreTimeInForce tif) {
            harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(harness.nextOrderId(), symbol,
                            1, side, 100, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, tif, false, ""))));
        }

        private void mark(String symbol, long price) {
            harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE,
                    0, TradingCommandCodec.encodeApplyMarkPrice(productLine == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand(symbol, 1, price, 100, 100,
                            ++sequence, harness.nextCommandTimestamp())
                            : new ApplyMarkPriceCommand(symbol, 1, price,
                            ++sequence, harness.nextCommandTimestamp()))));
        }

        private void drainRisk() {
            int rounds = 0;
            while (!harness.state().runtimeRiskScan("RISK-LONG").riskComplete()
                    || !harness.state().runtimeRiskScan("RISK-SHORT").riskComplete()) {
                if (++rounds > 1024) throw new IllegalStateException("risk scan did not drain");
                harness.execute(harness.command(CoreMessageType.CONTINUE_RISK_SCAN, CommandSource.OPERATIONS,
                        0, TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            }
        }

        private void queryCandidates() {
            var response = harness.state().apply(new CoreMessage(CoreMessageHeader.query(
                    CoreMessageType.ADL_CANDIDATE_QUERY, new UUID(411, sequence), productLine,
                    CommandSource.OPERATIONS, 1, 1, 0, harness.nextCommandTimestamp(), sequence),
                    CoreAdlQueryCodec.encodeQuery(asset, 256)));
            if (response.status() != ResponseStatus.OK
                    || CoreAdlQueryCodec.decodeCandidates(response.data()).stream()
                    .noneMatch(candidate -> candidate.userId() >= 1000 && candidate.symbol().equals("RISK-LONG"))) {
                throw new IllegalStateException("profitable delivery users missing from ADL candidates");
            }
        }

        private long funds(TradingCoreState state) {
            long result = 0;
            for (var user : state.users().values()) result = Math.addExact(result, user.totalUnits(asset));
            var treasury = state.treasuryState();
            for (Map<String, Long> ledger : java.util.List.of(treasury.feeBalances(), treasury.insuranceBalances(),
                    treasury.liquidationFeeBalances(), treasury.fundingResidualBalances(),
                    treasury.roundingResidualBalances(), treasury.clearingPnlBalances())) {
                result = Math.addExact(result, ledger.getOrDefault(asset, 0L));
            }
            return Math.subtractExact(result, treasury.insuranceDeficits().getOrDefault(asset, 0L));
        }

        @TearDown(Level.Trial)
        public void teardown() {
            try {
                TradingCoreState state = harness.state().tradingState();
                if (funds(state) != openingFunds || harness.acceptedMessages() != harness.terminalMessages()
                        || harness.acceptedCoreMessages() != harness.terminalCoreMessages()) {
                    throw new IllegalStateException("funds or terminal counters differ: funds=" + funds(state)
                            + "/" + openingFunds + " business=" + harness.acceptedMessages() + "/"
                            + harness.terminalMessages() + " core=" + harness.acceptedCoreMessages() + "/"
                            + harness.terminalCoreMessages());
                }
                if (productLine == ProductLine.OPTION) {
                    long shorts = state.riskState().liquidations().values().stream()
                            .filter(l -> l.userId() >= 1000 && l.status() == CoreLiquidationState.Status.PLANNED
                                    && l.signedQuantitySteps() < 0).count();
                    if (shorts != 256 || state.riskState().liquidations().values().stream()
                            .anyMatch(l -> l.userId() >= 1000 && l.signedQuantitySteps() > 0 && !l.terminal())) {
                        throw new IllegalStateException("option liquidation boundary differs");
                    }
                }
                for (long user = 1000; user < 1256; user++) {
                    var account = state.user(user);
                    if (account.positions().get("RISK-LONG").signedQuantitySteps() != 1
                            || account.positions().get("RISK-SHORT").signedQuantitySteps() != -10
                            || account.balances().get(asset).availableUnits() < 0
                            || account.balances().get(asset).lockedUnits() < 0) {
                        throw new IllegalStateException("position or balance changed during risk scan");
                    }
                }
                try (var recovered = LinearPerpetualBenchmarkSupport.Harness.restore(
                        harness.snapshotTemplate(accountLanes))) {
                    if (recovered.state().tradingState().businessStateHash() != state.businessStateHash()
                            || funds(recovered.state().tradingState()) != openingFunds) {
                        throw new IllegalStateException("snapshot recovery differs");
                    }
                }
            } finally {
                harness.close();
            }
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long acceptedBusinessOperations, terminalBusinessOperations;
        public long acceptedCoreMessages, terminalCoreMessages;
        public long unfinishedBusinessOperations, unfinishedCoreMessages, queries;
        @Setup(Level.Iteration) public void reset() {
            acceptedBusinessOperations = terminalBusinessOperations = acceptedCoreMessages = terminalCoreMessages = 0;
            unfinishedBusinessOperations = unfinishedCoreMessages = queries = 0;
        }
    }
}
