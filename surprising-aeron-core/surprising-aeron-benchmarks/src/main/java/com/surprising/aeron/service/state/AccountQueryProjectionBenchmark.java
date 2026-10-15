package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.query.RuntimeStateQueryService;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;

/** Actual bounded query projection/encoding on an explicitly owned cold Lane; excludes dispatch/transport. */
@State(Scope.Thread) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 10) @Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class AccountQueryProjectionBenchmark {
    @Param({"USER", "ORDER", "CLIENT"}) public String queryType;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private AccountLaneState lane;
    private long calls, startedCalls, startedMillis;
    private Interval interval;

    @Setup public void setup() {
        var product = ProductLine.LINEAR_PERPETUAL;
        identities = new RuntimeIdentityRegistry();
        runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), identities);
        var instrument = CoreInstrument.from(product, new RegisterInstrumentCommand("1",
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        runtime.registerInstrument(instrument);
        int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
        runtime.putUser(new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY));
        runtime.putBalance(new BalanceRuntime(7, asset, 1000, 150));
        runtime.putReservation(new ReservationRuntime(71, 7, symbol,
                ReservationKind.DERIVATIVE_MARGIN, asset, 100, 0, 0, 2));
        runtime.putPosition(identities.positionKey(7, "1"), new PositionRuntime(7, symbol, asset,
                CoreMarginMode.CROSS, CorePositionSide.NET, instrument, 2, 100, 200, 7, 50));
        runtime.putLeverage(new CoreLeverageKey(7, "1", CoreMarginMode.CROSS), 5_000_000);
        runtime.putOrder(new OrderRuntime(71, product, 7, symbol, instrument, CoreOrderSide.BUY,
                100, 100, 2, 0, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "query-71", new UUID(1, 71), 0, 0, 0,
                1000, 1000, 9, CoreOrderStatus.OPEN, 1));
        runtime.putClientOrder(7, identities.clientKey(7, "query-71"), 71);
        runtime.clearChangedKeys();
        lane = runtime.accountLaneForUser(7);
    }

    @Setup(Level.Iteration) public void beginInterval(IterationParams params) {
        startedCalls = calls;
        startedMillis = System.currentTimeMillis();
        interval = new Interval();
        interval.phase = params.getType().name();
        interval.begin();
    }

    @Benchmark public byte[] projectAndEncode() {
        byte[] result = switch (queryType) {
            case "USER" -> CoreStateQueryCodec.encodeUserState(
                    RuntimeStateQueryService.userState(runtime, lane, identities, 7).view());
            case "ORDER" -> CoreStateQueryCodec.encodeOrderState(
                    RuntimeStateQueryService.orderState(runtime, lane, identities, 71).view());
            case "CLIENT" -> CoreStateQueryCodec.encodeOrderState(
                    RuntimeStateQueryService.clientOrderState(runtime, lane, identities, 7, "query-71").view());
            default -> throw new IllegalArgumentException("unknown query type");
        };
        calls++;
        return result;
    }

    @TearDown(Level.Iteration) public void endInterval() {
        interval.end();
        interval.queries = calls - startedCalls;
        interval.commit();
        System.out.println("accountQueryInterval phase=" + interval.phase + " startEpochMillis=" + startedMillis
                + " endEpochMillis=" + System.currentTimeMillis() + " queries=" + interval.queries);
    }

    @TearDown public void close() { if (runtime != null) runtime.close(); }

    @jdk.jfr.Name("surprising.AccountQueryInterval")
    @jdk.jfr.StackTrace(false)
    public static class Interval extends jdk.jfr.Event { public String phase; public long queries; }
}
