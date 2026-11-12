package com.surprising.aeron.service.core;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.query.StateQueries;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;
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
    private AccountBook accountBook;
    private IdIndex identities;
    private AccountPartition lane;
    private long calls, startedCalls, startedMillis;
    private Interval interval;

    @Setup public void setup() {
        var product = ProductLine.LINEAR_PERPETUAL;
        identities = new IdIndex();
        accountBook = SnapshotLoader.loadSnapshot(TradingCoreState.empty(product), identities);
        var instrument = CoreInstrument.from(product, new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                0, 0, 0, -1, 0));
        accountBook.registerInstrument(instrument);
        int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
        accountBook.putUser(new UserAccount(product, 7, 3, CorePositionMode.ONE_WAY));
        accountBook.putBalance(new AccountBalance(7, asset, 1000, 150));
        accountBook.putReservation(new OrderFundsReservation(71, 7, symbol,
                ReservationKind.DERIVATIVE_MARGIN, asset, 100, 0, 0, 2));
        accountBook.putPosition(identities.positionKey(7, "1"), new Position(7, symbol, asset,
                CoreMarginMode.CROSS, CorePositionSide.NET, instrument, 2, 100, 200, 7, 50));
        accountBook.putLeverage(new CoreLeverageKey(7, "1", CoreMarginMode.CROSS), 5_000_000);
        accountBook.putOrder(new Order(71, product, 7, symbol, instrument, CoreOrderSide.BUY, 100, 2, 0, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "query-71", new UUID(1, 71), 0, 0, 0,
                1000, 1000, 9, CoreOrderStatus.OPEN, 1));
        accountBook.putClientOrder(7, identities.clientKey(7, "query-71"), 71);
        accountBook.clearChangedKeys();
        lane = accountBook.accountLaneForUser(7);
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
                    StateQueries.userState(accountBook, lane, identities, 7, false).view());
            case "ORDER" -> CoreStateQueryCodec.encodeOrderState(
                    StateQueries.orderState(accountBook, lane, identities, 71, false).view());
            case "CLIENT" -> CoreStateQueryCodec.encodeOrderState(
                    StateQueries.clientOrderState(accountBook, lane, identities, 7, "query-71").view());
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

    @TearDown public void close() { if (accountBook != null) accountBook.close(); }

    @jdk.jfr.Name("surprising.AccountQueryInterval")
    @jdk.jfr.StackTrace(false)
    public static class Interval extends jdk.jfr.Event { public String phase; public long queries; }
}
