package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 真实外部 Cluster 的账户控制路径采样；同账户命令存在依赖，不作为持续交易容量指标。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(1)
@Threads(1)
public class ClusterAccountControlBenchmark {
    @Param({"SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    public String productLine;
    private ProductLine product;
    private AeronClientPool client;
    private long sequence, operations;
    private String asset;
    private static final String SYMBOL = "CONTROL-USDT";
    private static final long USER = 900_000_001, MAKER = 900_000_002, POSITION_USER = 900_000_003;
    private static final long FUNDS = 1_000_000;

    @Setup(Level.Trial)
    public void setup() {
        product = ProductLine.valueOf(productLine);
        var type = ContractType.valueOf(product.contractTypeCode());
        asset = type.isInverse() ? "BTC" : "USDT";
        client = new AeronClientPool("account-control", product,
                List.of(System.getProperty("surprising.aeron.hostnames").split(",")), "127.0.0.1",
                Duration.ofSeconds(30), "account-control", UUID.randomUUID().toString(),
                new AeronClientCapacity(1, 1, 256, 64, 256, 32, 128));
        send(CoreMessageType.UPSERT_INSTRUMENT, 0, TradingCommandCodec.encodeUpsertInstrument(
                new UpsertInstrumentCommand(SYMBOL, 1, type.ordinal(), "BTC", "USDT", asset, 1, 1, 1,
                        100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? System.currentTimeMillis() + 3_600_000 : 0,
                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
        for (long user : new long[]{USER, MAKER, POSITION_USER}) balance(user, FUNDS);
        if (product.isDerivative()) {
            send(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    product == ProductLine.OPTION ? new ApplyMarkPriceCommand(SYMBOL, 1, 100, 100, 100, 1, System.currentTimeMillis())
                            : new ApplyMarkPriceCommand(SYMBOL, 1, 100, 1, System.currentTimeMillis())));
            for (boolean buy : new boolean[]{false, true}) send(CoreMessageType.PLACE_ORDER, buy ? POSITION_USER : MAKER,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(buy ? 9002 : 9001, SYMBOL, 1,
                            buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, 100, 10, false, CoreMarginMode.ISOLATED,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "control-" + buy)));
        }
        operations = 0;
    }

    private void send(CoreMessageType type, long user, byte[] payload) {
        var response = client.commandAsync(type, new UUID(90571, ++sequence), user, payload).join();
        if (response.commandStatus() != ResponseStatus.APPLIED)
            throw new IllegalStateException(type + " rejected: " + response.resultCode());
        operations++;
    }
    private void balance(long user, long delta) {
        send(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, delta)));
    }

    @Benchmark
    public long accountControls() {
        long before = operations;
        for (int i = 0; i < 100; i++) {
            balance(USER, 10); balance(USER, -10);
            if (!product.isDerivative()) continue;
            for (var mode : new CorePositionMode[]{CorePositionMode.HEDGE, CorePositionMode.ONE_WAY})
                send(CoreMessageType.UPDATE_POSITION_MODE, USER,
                        TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(mode)));
            if (product != ProductLine.OPTION) for (long leverage : new long[]{2_000_000, 1_000_000})
                send(CoreMessageType.UPDATE_LEVERAGE, USER, TradingCommandCodec.encodeUpdateLeverage(
                        new UpdateLeverageCommand(SYMBOL, CoreMarginMode.CROSS, leverage)));
            for (long delta : new long[]{10, -10}) send(CoreMessageType.ADJUST_POSITION_MARGIN, POSITION_USER,
                    TradingCommandCodec.encodeAdjustPositionMargin(new AdjustPositionMarginCommand(
                            SYMBOL, CoreMarginMode.ISOLATED, CorePositionSide.NET, delta)));
        }
        return operations - before;
    }

    @TearDown(Level.Trial)
    public void verify() {
        try {
            long total = 0, quantity = 0;
            for (long user : new long[]{USER, MAKER, POSITION_USER}) {
                var response = client.query(CoreMessageType.USER_STATE_QUERY, new UUID(90571, ++sequence), user, new byte[0]);
                if (response.status() != ResponseStatus.OK) throw new IllegalStateException("account query failed");
                var view = CoreStateQueryCodec.decodeUserState(response.data());
                for (var balance : view.balances()) total += balance.availableUnits() + balance.lockedUnits();
                long userQuantity = 0;
                for (var position : view.positions()) userQuantity += position.signedQuantitySteps();
                long expectedQuantity = !product.isDerivative() || user == USER ? 0
                        : user == MAKER ? -10 : 10;
                if (userQuantity != expectedQuantity)
                    throw new IllegalStateException("control user position mismatch: " + user);
                quantity += userQuantity;
                if (!view.reservations().isEmpty()) throw new IllegalStateException("unfinished control fixture order");
            }
            if (total != 3 * FUNDS || quantity != 0) throw new IllegalStateException("control funds/position mismatch");
            System.out.printf("accountControlVerify=PASS product=%s terminalBusinessOperations=%d unfinished=0 fundsDiff=0 netPosition=0%n", product, operations);
        } finally { if (client != null) client.close(); }
    }
}
