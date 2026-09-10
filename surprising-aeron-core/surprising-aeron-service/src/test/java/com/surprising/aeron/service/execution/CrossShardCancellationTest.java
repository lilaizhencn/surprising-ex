package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CrossShardCancellationTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void liquidationCancellationPollsBlockedShardAndRecoversTheSameFunds() throws Exception {
        String property = "surprising.aeron.matching-engines";
        String previous = System.getProperty(property);
        System.setProperty(property, "2");
        var release = new CountDownLatch(1);
        try (var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            String first = "SHARD0-USDT";
            String second = "SHARD1-USDT";
            while (state.matchingAdapter.matcherShardId(first) == state.matchingAdapter.matcherShardId(second))
                second = "X" + second;
            seed(state, first, 1, 1000);
            seed(state, second, 3, 2000);
            applied(state, mark(first, 1, 2));
            applied(state, mark(second, 1, 2));
            CoreLiquidationWorkView work = work(state);
            for (int i = 0; work.riskScanPending() && i < 16; i++) {
                applied(state, command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(16))));
                work = work(state);
            }
            assertThat(work.riskScanPending()).isFalse();
            assertThat(work.actions()).hasSize(2);
            var batch = command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work, 0, 0)));
            byte[] snapshot = state.snapshot(500);
            var entered = new CountDownLatch(1);
            int shard = state.matchingAdapter.matcherShardId(first);
            long token = state.matcherPipeline.submitControl(shard, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked matcher timed out"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return Boolean.TRUE;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var pending = state.apply(batch);
            assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(release.getCount()).isOne();
            assertThat(state.pendingMatching(state.matchingSequence(batch.header().commandId()))
                    .crossShardCancellationStarted).isTrue();
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            Object complete;
            do { complete = state.matcherPipeline.pollControl(shard, token); Thread.onSpinWait(); }
            while (complete == null && System.nanoTime() < deadline);
            assertThat(complete).isEqualTo(Boolean.TRUE);
            var terminal = CoreTestCompletion.completeMatchingSynchronously(state,
                    state.matchingSequence(batch.header().commandId()), TIME, batch.header().sourceSequence());
            assertThat(terminal.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().order(1003).status()).isEqualTo(com.surprising.aeron.service.state.model.CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(2003).status()).isEqualTo(com.surprising.aeron.service.state.model.CoreOrderStatus.CANCELED);
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                applied(restored, batch);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(restored.tradingState().users()).isEqualTo(state.tradingState().users());
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private void seed(TradingCoreRuntime state, String symbol, long user, long order) {
        applied(state, command(CoreMessageType.UPSERT_INSTRUMENT, 0,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol, 1,
                        ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0))));
        applied(state, mark(symbol, 100, 1));
        applied(state, command(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 110))));
        applied(state, command(CoreMessageType.ADJUST_BALANCE, user + 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        applied(state, place(user + 1, symbol, order + 1, CoreOrderSide.SELL, 100, 10));
        applied(state, place(user, symbol, order + 2, CoreOrderSide.BUY, 100, 10));
        applied(state, place(user, symbol, order + 3, CoreOrderSide.BUY, 1, 1));
    }

    private CoreMessage place(long user, String symbol, long order, CoreOrderSide side, long price, long quantity) {
        return command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(order, symbol, 1, side, price, quantity, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + order)));
    }
    private CoreMessage mark(String symbol, long price, long revision) {
        return command(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(symbol, 1, price, revision, TIME)));
    }
    private CoreLiquidationWorkView work(TradingCoreRuntime state) {
        var query = command(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,
                        CoreLiquidationWorkView.Purpose.EXECUTION, 0, 100, 1_048_576));
        return CoreLiquidationWorkCodec.decodeWork(state.apply(query).data());
    }
    private CoreMessage command(CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        var id = new UUID(881, next);
        var header = type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type, id, ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                881, next, user, TIME, next)
                : CoreMessageHeader.command(type, id, ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                881, next, user, TIME, next);
        return new CoreMessage(header, payload);
    }
    private static void applied(TradingCoreRuntime state, CoreMessage command) {
        var response = state.apply(command);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING)
            response = CoreTestCompletion.completeMatchingSynchronously(state,
                    state.matchingSequence(command.header().commandId()), TIME, command.header().sourceSequence());
        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
    }
}
