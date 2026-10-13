package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 真实Lane前置校验与Matcher撤单顺序；不涉及快照、恢复或负载测试。 */
class CoreLiquidationLaneCheckTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test
    void cancellationBudgetResumesAcrossLanesWithoutOwnerAccountReads() throws Exception {
        try (var core = prepared()) {
            long funds = funds(core);
            int pages = 0;
            for (var work = work(core); !work.actions().isEmpty(); work = work(core)) {
                assertThat(++pages).isLessThanOrEqualTo(4);
                var batch = new ExecuteLiquidationBatchCommand(
                        ExecuteLiquidationBatchCommand.fromWork(work, 0, 0).actions(), 1, 0, null, 0);
                CoreResponse result;
                long handoff = core.runtimeState.laneHandoffEpoch();
                try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    result = applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(batch));
                }
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                var summary = CoreLiquidationBatchResultCodec.decode(result.data());
                assertThat(summary.processedOrders()).isEqualTo(1);
                if (pages == 1) assertThat(summary.pendingActions()).isEqualTo(2);
            }
            assertThat(pages).isEqualTo(4);
            assertThat(funds(core)).isEqualTo(funds);
            assertThat(core.tradingState().orders().values()).hasSize(4)
                    .allSatisfy(order -> assertThat(order.status()).isEqualTo(CoreOrderStatus.CANCELED));
            assertThat(core.tradingState().users().get(1L).positions().values())
                    .allSatisfy(position -> assertThat(position.signedQuantitySteps()).isZero());
            assertThat(core.tradingState().users().get(7L).positions().values())
                    .allSatisfy(position -> assertThat(position.signedQuantitySteps()).isZero());
            core.assertClusterCallbackComplete();
        }
    }

    @Test
    void staleBatchPreservesAccountOrdersAndMatcherOrders() throws Exception {
        try (var core = prepared()) {
            var stale = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 2, 3, TIME)));
            var before = core.tradingState();
            CoreResponse result;
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                result = applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(stale));
            }
            var summary = CoreLiquidationBatchResultCodec.decode(result.data());
            assertThat(summary.obsoleteActions()).isEqualTo(2);
            assertThat(summary.appliedActions()).isZero();
            assertThat(summary.processedOrders()).isZero();
            assertThat(core.tradingState().users()).isEqualTo(before.users());
            assertThat(core.tradingState().orders()).isEqualTo(before.orders());
            assertThat(core.tradingState().treasuryState()).isEqualTo(before.treasuryState());
            // 后续真实撤单必须成功，证明过期强平没有先从Matcher删除有效订单。
            cancelRemainingOrders(core);
        }
    }

    @Test
    void obsoleteLaneRiskCancelsOnlyPlanAndLeavesRestingOrdersValid() throws Exception {
        try (var core = prepared()) {
            var action = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0).actions().stream().filter(value -> value.userId() == 1).findFirst().orElseThrow();
            var instrument = core.runtimeState.instrument("1");
            long key = core.identities.findPositionKeyValue(1, instrument, CorePositionSide.NET);
            core.runtimeState.dispatchControlLanes(core.runtimeState.topology().accountLaneMask(1), ignored -> {
                var risk = core.runtimeState.riskSnapshot(key);
                core.runtimeState.putRiskSnapshot(key, new RiskSnapshotRuntime(risk.userId(), risk.symbolId(),
                        risk.positionSide(), risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm(), CoreRiskStatus.NORMAL));
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!core.runtimeState.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("risk fixture Lane timeout");
                Thread.onSpinWait();
            }
            core.runtimeState.clearCommittedChanges(core.identities);
            var before = core.tradingState();
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                                List.of(action), 10, 0, null, 0)));
            }
            var after = core.tradingState();
            assertThat(after.riskState().liquidations().get(action.liquidationId()).status())
                    .isEqualTo(CoreLiquidationState.Status.CANCELED);
            assertThat(after.users().get(1L).balances()).isEqualTo(before.users().get(1L).balances());
            assertThat(after.users().get(1L).positions()).isEqualTo(before.users().get(1L).positions());
            assertThat(after.orders()).isEqualTo(before.orders());
            assertThat(after.treasuryState()).isEqualTo(before.treasuryState());
            cancelRemainingOrders(core);
        }
    }

    @Test
    void staleSingleRejectsBeforeMatcherCancellation() throws Exception {
        try (var core = prepared()) {
            var action = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0).actions().getFirst();
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 2, 3, TIME)));
            var before = core.tradingState();
            var command = new ExecuteLiquidationCommand(action.liquidationId(), action.triggerPriceSequence(),
                    action.executionPriceTicks(), 0);
            try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                var response = apply(core, CoreMessageType.EXECUTE_LIQUIDATION, 0,
                        TradingCommandCodec.encodeExecuteLiquidation(command));
                assertThat(response.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(response.resultCode()).isEqualTo(CoreResultCode.STALE_MARK_PRICE);
            }
            assertThat(core.tradingState().users()).isEqualTo(before.users());
            assertThat(core.tradingState().orders()).isEqualTo(before.orders());
            assertThat(core.tradingState().treasuryState()).isEqualTo(before.treasuryState());
            cancelRemainingOrders(core);
        }
    }

    private TradingCoreRuntime prepared() {
        var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL);
        try {
            applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0)));
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long user : new long[]{1, 2, 7}) applied(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 2 ? 10_000 : 110)));
            place(core, 2, 100, CoreOrderSide.SELL, 100, 20, false);
            for (long user : new long[]{1, 7}) {
                place(core, user, 100 + user, CoreOrderSide.BUY, 100, 10, false);
                for (int index = 0; index < 2; index++)
                    place(core, user, 200 + user * 10 + index, CoreOrderSide.SELL, 110, 1, true);
            }
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 1, 2, TIME)));
            for (int polls = 0; core.runtimeState.firstRiskIncompleteScan() != null; polls++) {
                assertThat(polls).isLessThan(20);
                applied(core, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
            }
            assertThat(work(core).actions()).hasSize(2);
            return core;
        } catch (Throwable failure) { core.close(); throw failure; }
    }

    private void cancelRemainingOrders(TradingCoreRuntime core) throws Exception {
        try (var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            for (long user : new long[]{1, 7}) for (int index = 0; index < 2; index++)
                applied(core, CoreMessageType.CANCEL_ORDER, user,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(200 + user * 10 + index)));
        }
        assertThat(core.tradingState().orders()).isEmpty();
        core.assertClusterCallbackComplete();
    }

    private void place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long price,
                       long quantity, boolean reduceOnly) {
        applied(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, price, quantity, reduceOnly, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "lane-check-" + id)));
    }

    private CoreLiquidationWorkView work(TradingCoreRuntime core) {
        var response = apply(core, CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(core.productLine, CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1000, 1_048_576));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    private long funds(TradingCoreRuntime core) {
        var state = core.tradingState();
        long total = state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum();
        total = Math.addExact(total, state.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, state.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L));
        return Math.subtractExact(total, state.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));
    }

    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        var response = apply(core, type, user, payload);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }

    private CoreResponse apply(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long next = ++sequence;
        var id = new UUID(951, next);
        var header = type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type, id, core.productLine, CommandSource.OPERATIONS, 951, next, user, TIME, next)
                : CoreMessageHeader.command(type, id, core.productLine, CommandSource.OPERATIONS, 951, next, user, TIME, next);
        var message = new CoreMessage(header, payload);
        return type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreTestCompletion.querySynchronously(core, message)
                : CoreTestCompletion.applyAsynchronously(core, message);
    }
}
