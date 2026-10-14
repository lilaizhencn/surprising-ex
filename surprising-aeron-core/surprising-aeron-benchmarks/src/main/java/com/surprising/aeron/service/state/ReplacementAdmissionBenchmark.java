package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.openjdk.jmh.annotations.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Read-only replacement preflight with 256 outstanding slots and actual Account workers.
 * Immutable input/market metadata are setup fixtures; result allocation is measured.
 * This stage excludes decoding, Matcher execution and account settlement, so it is not Core capacity. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class ReplacementAdmissionBenchmark {
    private static final int WINDOW = 256;
    @Param({"1", "4"}) public int laneCount;
    @Param({"LINEAR_PERPETUAL", "SPOT"}) public String productLine;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private CoreOrderDecisionResolver.Context decision;
    private final AmendOrderCommand[] commands = new AmendOrderCommand[WINDOW];
    private final ReplacementAdmissionEvent[] pending = new ReplacementAdmissionEvent[WINDOW];
    private long sequence;

    @Setup public void setup() {
        var product = ProductLine.valueOf(productLine);
        var instrument = CoreInstrument.from(product,
                new RegisterInstrumentCommand("1", product == ProductLine.SPOT
                        ? ContractType.SPOT.ordinal() : ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000,
                        0, 0, 0, -1, 0, 10_000_000, 10_000, 0, 10_000,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000, 100_000, 50_000))));
        var users = new HashMap<Long, CoreUserState>();
        var orders = new HashMap<Long, CoreOrderState>();
        for (int i = 0; i < WINDOW; i++) {
            long user = i + 1, original = 1_000 + user;
            users.put(user, new CoreUserState(product, user, 1,
                    Map.of("USDT", new AssetBalance("USDT", 800, 100)),
                    Map.of(original, OrderReservation.create(original, "1", product == ProductLine.SPOT ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN,
                            "USDT", 100, 1)), Map.of()));
            orders.put(original, new CoreOrderState(original, product, user, "1",
                    CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "original", new UUID(1, user),
                    0, 0, CoreOrderStatus.OPEN, 1));
            commands[i] = new AmendOrderCommand(original, 2_000 + user, "replacement", 101L, 1L,
                    CoreTimeInForce.GTC, false);
        }
        identities = new RuntimeIdentityRegistry();
        // Initialize the fixture once; there is no snapshot or restore operation in measurement.
        runtime = RuntimeStateProjector.project(new TradingCoreState(product, 1,
                users, orders, Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty()),
                identities, new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, laneCount,
                        LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, WINDOW, WINDOW, WINDOW));
        decision = new CoreOrderDecisionResolver.Context(instrument, identities.symbolId("1"),
                new MarkPriceRuntime(identities.symbolId("1"), instrument, 100, 1, 1_000),
                FeePolicyBook.EMPTY, 1_001, runtime.treasury().settlementStatus(identities.symbolId("1")));
        runtime.startAccountLanes();
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long admitAmendments() {
        for (int i = 0; i < WINDOW; i++)
            pending[i] = runtime.dispatchReplacementAdmission(++sequence, i + 1, commands[i].originalOrderId(),
                    commands[i], null, decision, 0, identities);
        long reservation = 0;
        for (int i = 0; i < WINDOW; i++) {
            var event = pending[i];
            while (!event.complete()) { runtime.assertAccountLanesHealthy(); Thread.onSpinWait(); }
            if (event.rejection() != null) throw new IllegalStateException("replacement rejected", event.rejection());
            reservation += event.admission().requiredReservationUnits();
            runtime.releaseReplacementAdmission(event);
            pending[i] = null;
        }
        runtime.takeAccountAdmissionReadyShardMask();
        return reservation;
    }

    @TearDown public void close() { runtime.close(); }
}
