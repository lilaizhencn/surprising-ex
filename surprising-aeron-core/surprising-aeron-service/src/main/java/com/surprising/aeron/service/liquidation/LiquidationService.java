package com.surprising.aeron.service.liquidation;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.treasury.InsuranceAllocationPolicy;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LiquidationService {

    /** 新建或复用已经可回收的单笔强平续步，保留校验及账户步骤对象供命令槽复用，并派发目标账户 Lane。 */
    public static ExecutionWork beginExecution(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CancellationOrder> canceledOrders, long nextCursorOrderId,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null || nextCursorOrderId < 0
                || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation account stage");
        }
        runtime.assertOwner();
        ExecutionWork work = reuse == null || !reuse.reusable() ? new ExecutionWork() : reuse;
        long laneMask = work.prepare(command, canceledOrders, nextCursorOrderId, runtime, identities);
        runtime.dispatchControlLanes(laneMask, work);
        return work;
    }

    /** Matcher 撤单前的账户前置检查，随命令槽复用数组。Owner 准备动作路由，所属 Lane 读取强平、持仓和风险事实并写结果字节。Owner 收到完成位后才可选择要撤单的动作，回收时释放本次引用。 */
    public static final class ExecutionCheck implements java.util.function.IntFunction<Object> {
        private static final byte CHECK_LANE = 0, EXECUTABLE = 1, OBSOLETE = 2, STALE_PRICE = 3;
        /** 各检查动作从所属 Lane 捕获的强平记录引用，检查结束前不供 Owner 消费。 */
        private RiskScanner.LiquidationOrder[] liquidations = new RiskScanner.LiquidationOrder[4];
        private int[] laneIds = new int[4];
        /** 单笔检查直接读取原不可变命令。 */
        private ExecuteLiquidationCommand singleCommand;
        /** 批检查直接读取原不可变动作列表，不复制价格、编号和游标。 */
        private ExecuteLiquidationBatchCommand batchCommand;
        private RuntimeIdentityRegistry identities;
        /** 各动作的检查结果字节，Lane 写入后通过既有控制完成位发布给 Owner。 */
        private byte[] results = new byte[4];
        private TradingAccountRuntime runtime;
        private int count;
        /** 本批前置检查涉及的账户 Lane 位集合，Owner 据此只派发相关 Lane。 */
        private long laneMask;
        /** dispatched：检查任务是否已经交给相关 Lane，避免 Owner 重复派发。done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。 */
        private boolean dispatched, done;

        /** 为单笔强平初始化一个检查槽，保留原命令并定位需要读取的 Lane。 */
        public void prepare(ExecuteLiquidationCommand command, TradingAccountRuntime runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, identities, 1);
            singleCommand = command;
            prepareAction(0, command.liquidationId());
        }

        /** 按批命令动作数量准备检查槽，保留原动作列表并定位 Lane 路由，让一批动作共用一次账户检查派发。 */
        public void prepare(ExecuteLiquidationBatchCommand command, TradingAccountRuntime runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, identities, command.actions().size());
            batchCommand = command;
            for (int index = 0; index < count; index++) {
                var action = command.actions().get(index);
                prepareAction(index, action.liquidationId());
            }
        }

        /** Owner 确认旧输入已释放，按需一起扩容所有并行数组，再清空本次参与 Lane 和完成标记，避免把两条命令混在一起。 */
        private void initialize(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, int count) {
            runtime.assertOwner();
            if (this.runtime != null) throw new IllegalStateException("liquidation checks already prepared");
            if (count > results.length) {
                int capacity = Math.max(count, Math.multiplyExact(results.length, 2));
                liquidations = new RiskScanner.LiquidationOrder[capacity];
                laneIds = new int[capacity]; results = new byte[capacity];
            }
            this.runtime = runtime; this.identities = identities;
            this.count = count; laneMask = 0; dispatched = done = false;
        }

        private void prepareAction(int index, long liquidationId) {
            results[index] = OBSOLETE; laneIds[index] = -1;
            var route = runtime.liquidationRoute(liquidationId);
            long userId = route == null ? 0 : route.userId();
            if (userId == 0) return;
            laneIds[index] = runtime.topology().accountLaneId(userId);
            results[index] = CHECK_LANE; laneMask |= 1L << laneIds[index];
        }

        /** 判断当前是否已绑定一条待检查命令，绑定后必须完成或清理引用才能再次准备。 */
        public boolean prepared() { return runtime != null; }

        /** Owner 只派发一次相关 Lane 并非阻塞等待结果。 */
        public boolean poll() {
            if (done) return true;
            runtime.assertOwner();
            if (laneMask == 0) { done = true; return true; }
            if (!dispatched) {
                runtime.dispatchControlLanes(laneMask, this);
                dispatched = true;
            }
            if (!runtime.pollControlLanes()) return false;
            done = true;
            return true;
        }

        /** 所属 Lane 逐项核对强平阶段和撤单游标，再检查标记价、产品可强平性、持仓和风险。 */
        @Override public Object apply(int laneId) {
            for (int index = 0; index < count; index++) {
                if (laneIds[index] != laneId || results[index] != CHECK_LANE) continue;
                if (singleCommand != null) {
                    checkAction(index, singleCommand.liquidationId(), singleCommand.triggerPriceSequence(),
                            singleCommand.executionPriceTicks(), singleCommand.cursorOrderId());
                } else {
                    var action = batchCommand.actions().get(index);
                    checkAction(index, action.liquidationId(), action.triggerPriceSequence(),
                            action.executionPriceTicks(), action.cursorOrderId());
                }
            }
            return null;
        }

        private void checkAction(int index, long liquidationId, long priceSequence, long executionPriceTicks, long cursor) {
            RiskScanner.LiquidationOrder value = runtime.liquidation(liquidationId);
            liquidations[index] = value;
            if (value == null || (value.status() != CoreLiquidationState.Status.PLANNED
                    && value.status() != CoreLiquidationState.Status.ORDERED)) {
                results[index] = OBSOLETE; return;
            }
            if (value.status() == CoreLiquidationState.Status.PLANNED && cursor != 0
                    || value.status() == CoreLiquidationState.Status.ORDERED && cursor != value.nextCancelOrderId())
                throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT", "liquidation cursor does not match state");
            MarkPrice mark = runtime.markPrice(value.symbolId());
            if (mark == null || mark.priceSequence() != value.triggerPriceSequence()
                    || priceSequence > 0 && priceSequence != value.triggerPriceSequence()
                    || executionPriceTicks != mark.markPriceTicks()) {
                results[index] = STALE_PRICE; return;
            }
            CoreInstrument instrument = runtime.instrument(identities.instrumentId(value.symbolId()));
            if (instrument == null || !RiskMarginCalculator.canLiquidate(instrument.contractType(), value.signedQuantitySteps())) {
                results[index] = OBSOLETE; return;
            }
            long positionKey = identities.findPositionKeyValueInLane(runtime.currentLaneCommandScope(),
                    value.userId(), instrument, value.positionSide());
            results[index] = positionKey != 0 && LiquidationService.executable(
                    runtime, value, instrument, positionKey) ? EXECUTABLE : OBSOLETE;
        }

        /** 检查结果已完成后取得该动作捕获的强平记录。 */
        public RiskScanner.LiquidationOrder liquidation(int index) { requireResult(index); return liquidations[index]; }

        /** 检查完成后只判断该动作是否可执行撤单。 */
        public boolean cancelsOrders(int index) { requireResult(index); return results[index] == EXECUTABLE; }

        /** 检查完成后判断动作是否可执行。若因标记价过期而不可执行，则在本动作消费时返回 STALE_MARK_PRICE 业务拒绝。 */
        public boolean executable(int index) {
            requireResult(index);
            if (results[index] == STALE_PRICE)
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
            return results[index] == EXECUTABLE;
        }

        /** 要求全部 Lane 检查已经完成且动作下标有效，避免提前读取尚未发布的数组结果。 */
        private void requireResult(int index) {
            if (!done || index < 0 || index >= count)
                throw new IllegalStateException("liquidation Lane check is not complete");
        }

        /** 释放本批强平引用并清空参与标记，保留各数组容量供下次命令槽使用。 */
        public void clearReferences() {
            java.util.Arrays.fill(liquidations, 0, count, null);
            singleCommand = null; batchCommand = null;
            runtime = null; identities = null; count = 0; laneMask = 0; dispatched = done = false;
        }
    }

    /** 批量执行中的一笔账户步骤，直接保留原动作、Matcher 撤单回执及下一撤单游标。 */
    public record ExecutionRequest(ExecuteLiquidationBatchAction action,
                                   Collection<CancellationOrder> canceledOrders,
                                   long nextCursorOrderId) {
        /** 要求批量账户步骤有具体动作，下一撤单游标不能为负，0 表示本次可以进入最终执行。 */
        public ExecutionRequest {
            if (action == null || nextCursorOrderId < 0) {
                throw new IllegalArgumentException("invalid liquidation batch request");
            }
        }
    }

    /** Owner 根据各动作源用户合并 Lane 路由，一次派发多个账户执行步骤。 */
    public static java.util.function.BooleanSupplier beginExecutionBatch(
            List<ExecutionRequest> requests, long liquidationFeeRatePpm,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (requests == null || requests.isEmpty() || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid liquidation execution batch");
        }
        runtime.assertOwner();
        BatchExecutionStage[] stages = new BatchExecutionStage[requests.size()];
        long laneMask = 0;
        int[] laneIds = new int[requests.size()];
        for (int index = 0; index < requests.size(); index++) {
            var route = runtime.liquidationRoute(requests.get(index).action().liquidationId());
            if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
            laneIds[index] = runtime.topology().accountLaneId(route.userId());
            laneMask |= 1L << laneIds[index];
        }
        runtime.dispatchControlLanes(laneMask, lane -> {
            for (int index = 0; index < stages.length; index++) {
                if (laneIds[index] != lane) continue;
                var request = requests.get(index);
                var action = request.action();
                stages[index] = new BatchExecutionStage();
                stages[index].execute(action.liquidationId(), action.triggerPriceSequence(),
                        action.executionPriceTicks(), liquidationFeeRatePpm, action.cursorOrderId(), request.canceledOrders(),
                        request.nextCursorOrderId(), runtime, identities);
            }
            return null;
        });
        return new BatchFinancialWork(runtime, stages);
    }

    /** 一批独立强平账户步骤的资金交接续步。各 Lane 各写自己的结果，Owner 收集后把资金差额交账本逐项应用，账本完成后才一次提交本批全局修订号。 */
    private static final class BatchFinancialWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier {
        /** 本批各请求的账户执行步骤，所属 Lane 完成后 Owner 读取其用户与修订信息。 */
        private final BatchExecutionStage[] stages;
        /** done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。 */
        private boolean done;

        /** 绑定这批包含资金回执的账户步骤，初始化一次账本命令，供 Owner 后续轮询完成。 */
        private BatchFinancialWork(TradingAccountRuntime runtime, BatchExecutionStage[] stages) {
            initializeLedgerCommand(runtime);
            this.stages = stages;
        }

        /** Owner 先等全部账户步骤，登记有资金结果的用户并累计修订号增量。 */
        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            long revisionDelta = 0;
            for (int index = 0; index < stages.length; index++) {
                BatchExecutionStage stage = stages[index];
                if (stage.liquidated)
                    runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
                revisionDelta = Math.addExact(revisionDelta, stage.canceledOrdersApplied ? 2 : 1);
            }
            Math.addExact(runtime.revision(), revisionDelta);
            runtime.dispatchLedgerCommand(this);
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), revisionDelta));
            done = true;
            return true;
        }

        /** 账本按请求数组顺序应用各非空资金差额，单纯撤单游标推进或失效计划没有额外资金回执。 */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            for (BatchExecutionStage stage : stages) {
                stage.applyLedger(ledger);
            }
        }
    }

    /** 一笔强平在所属账户 Lane 内完成校验、撤单解冻及平仓，保留 Owner 所需身份和撤单标记、账本所需两项金额。 */
    private static final class BatchExecutionStage {
        /** Owner 据此确定修订增量。 */
        private boolean canceledOrdersApplied;
        /** 本次账户执行的源用户身份，Owner 据此登记结算变更。 */
        private long userId;
        private int assetId;
        private long positionKey;
        private boolean liquidated;
        private long insuranceDelta, deficitUnits;

        /** 临时输入仅在本次调用中使用，Owner 所需结果写回本对象。 */
        private void execute(long liquidationId, long triggerPriceSequence, long executionPriceTicks,
                             long liquidationFeeRatePpm, long cursorOrderId,
                             Collection<CancellationOrder> canceledOrders,
                             long nextCursorOrderId,
                             TradingAccountRuntime runtime,
                             RuntimeIdentityRegistry identities) {
            liquidated = false;
            if (!runtime.productLine().isDerivative()) {
                throw new IllegalArgumentException("liquidation requires derivative product");
            }
            RiskScanner.LiquidationOrder liquidation = runtime.liquidation(liquidationId);
            if (liquidation == null) {
                throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
            }
            boolean advance = nextCursorOrderId != 0;
            if (advance) {
                if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                        && liquidation.nextCancelOrderId() != cursorOrderId) {
                    throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                            "liquidation cancellation cursor does not match state");
                }
            } else {
                if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                        && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
                }
                validatePrice(runtime, liquidation, triggerPriceSequence, executionPriceTicks);
            }
            boolean hasCancellations = canceledOrders != null && !canceledOrders.isEmpty();
            if (hasCancellations) {
                for (var order : canceledOrders) {
                    if (order == null || order.userId() != liquidation.userId()) {
                        throw new IllegalArgumentException("liquidation cancellation must belong to its account");
                    }
                }
            }
            CoreInstrument instrument = advance ? null : runtime.instrument(identities.instrumentId(liquidation.symbolId()));
            AccountPartition scoped = runtime.currentLaneCommandScope();
            long positionKey = instrument == null ? 0 : scoped == null
                    ? identities.findPositionKeyValue(liquidation.userId(), instrument, liquidation.positionSide())
                    : identities.findPositionKeyValueInLane(scoped, liquidation.userId(), instrument, liquidation.positionSide());
            Position position = scoped == null || positionKey == 0 ? null : scoped.position(positionKey);
            int assetId = instrument == null ? 0 : scoped == null ? identities.assetId(instrument.settleAsset())
                    : position == null ? 0 : position.assetId();
            if (position != null && !identities.asset(assetId).equals(instrument.settleAsset()))
                throw new IllegalStateException("liquidation position settlement asset differs from instrument");
            this.userId = liquidation.userId();
            this.assetId = assetId;
            this.positionKey = positionKey;
            boolean obsolete = !advance && !executable(runtime, liquidation, instrument, positionKey);
            canceledOrdersApplied = !obsolete && hasCancellations;
            if (canceledOrdersApplied) for (var canceledOrder : canceledOrders) {
                long orderId = canceledOrder.orderId();
                Order order = runtime.order(orderId);
                if (order == null || order.userId() != liquidation.userId() || order.canceled()
                        || order.remainingQuantitySteps() == 0) {
                    throw new IllegalArgumentException("runtime liquidation cancellation requires open orders");
                }
                OrderFundsReservation reservation = runtime.reservation(orderId);
                if (reservation == null) {
                    throw new IllegalStateException("runtime liquidation reservation is missing: " + orderId);
                }
                runtime.cancelOrder(orderId, liquidation.userId(), reservation.reservedUnits());
            }
            if (obsolete) {
                runtime.replaceLiquidation(copy(liquidation, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
                runtime.advanceUserRevision(liquidation.userId());
                return;
            }
            if (advance) {
                runtime.replaceLiquidation(new RiskScanner.LiquidationOrder(liquidation.liquidationId(), liquidation.userId(),
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(), liquidation.instrument(),
                        liquidation.triggerPriceSequence(),
                        liquidation.signedQuantitySteps(), liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), CoreLiquidationState.Status.ORDERED, nextCursorOrderId));
                return;
            }
            executeAccountLiquidation(executionPriceTicks, liquidationFeeRatePpm, runtime, liquidation, instrument);
        }

        private void executeAccountLiquidation(long executionPriceTicks, long liquidationFeeRatePpm,
                TradingAccountRuntime runtime, RiskScanner.LiquidationOrder liquidation, CoreInstrument instrument) {
            Position position = runtime.position(positionKey);
            AccountBalance balance = runtime.balance(liquidation.userId(), assetId);
            if (position == null || balance == null) {
                throw new IllegalStateException("runtime liquidation entities are missing");
            }

            long currentAbs = Math.absExact(position.signedQuantitySteps());
            long closeQuantity = liquidation.closeQuantitySteps();
            long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
            long releasedMargin = position.positionMarginUnits() == 0 ? 0
                    : com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(position.positionMarginUnits(), closeQuantity, currentAbs);
            long signedCloseQuantity = position.signedQuantitySteps() > 0
                    ? closeQuantity : Math.negateExact(closeQuantity);
            long pnl = instrument.contractType().isOption()
                    ? OptionContractMath.optionMarketValueUnits(instrument, signedCloseQuantity,
                    executionPriceTicks)
                    : CoreContractMath.pnlUnits(instrument, signedCloseQuantity,
                    position.entryPriceTicks(), executionPriceTicks);
            long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                    executionPriceTicks, closeQuantity, liquidationFeeRatePpm));
            long available = balance.availableUnits();
            long locked = balance.lockedUnits();
            long appliedPnl;
            long collectedFee;
            if (liquidation.marginMode() == CoreMarginMode.ISOLATED) {
                if (pnl < 0) {
                    long consumedMargin = Math.min(releasedMargin, Math.negateExact(pnl));
                    long remainingMargin = Math.subtractExact(releasedMargin, consumedMargin);
                    locked = Math.subtractExact(locked, releasedMargin);
                    available = Math.addExact(available, remainingMargin);
                    appliedPnl = Math.negateExact(consumedMargin);
                    collectedFee = Math.min(feeDue, remainingMargin);
                } else {
                    locked = Math.subtractExact(locked, releasedMargin);
                    available = Math.addExact(available, Math.addExact(releasedMargin, pnl));
                    appliedPnl = pnl;
                    collectedFee = Math.min(feeDue, Math.addExact(releasedMargin, pnl));
                }
                available = Math.subtractExact(available, collectedFee);
            } else {
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, releasedMargin);
                if (pnl >= 0) {
                    available = Math.addExact(available, pnl);
                    appliedPnl = pnl;
                } else {
                    long debit = Math.min(available, Math.negateExact(pnl));
                    available = Math.subtractExact(available, debit);
                    appliedPnl = Math.negateExact(debit);
                }
                collectedFee = Math.min(available, feeDue);
                available = Math.subtractExact(available, collectedFee);
            }
            AccountBalance nextBalance = new AccountBalance(balance.userId(), balance.assetId(), available, locked);
            long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                    Math.negateExact(Math.min(0, appliedPnl))) : 0;

            long nextQuantity = remainingAbs == 0 ? 0
                    : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
            long nextEntryValue = remainingAbs == 0 ? 0
                    : com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(position.entryValueTicks(), remainingAbs, currentAbs);
            Position nextPosition = new Position(position.userId(), position.symbolId(), position.assetId(),
                    position.marginMode(), position.positionSide(), position.instrument(),
                    nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                    Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : pnl),
                    Math.subtractExact(position.positionMarginUnits(), releasedMargin));
            long insuranceDelta = Math.addExact(Math.negateExact(appliedPnl), collectedFee);
            CoreLiquidationState.Status nextStatus = uncovered > 0
                    ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED;

            runtime.replaceBalance(nextBalance);
            runtime.replacePosition(positionKey, nextPosition);
            runtime.replaceLiquidation(copy(liquidation, uncovered, executionPriceTicks,
                    liquidationFeeRatePpm, nextStatus, collectedFee));
            runtime.advanceUserRevision(liquidation.userId());
            // 所属 Lane 保存实际两项金额，Owner 收齐账户完成位后交原账本消费。
            this.insuranceDelta = insuranceDelta;
            this.deficitUnits = uncovered;
            this.liquidated = true;
        }

        /** 账本按原次序预先算好全部新值，再写保险、亏空和清算。 */
        private void applyLedger(TreasuryRuntime ledger) {
            if (!liquidated) return;
            long nextInsurance = Math.addExact(ledger.insurance(assetId), insuranceDelta);
            long nextDeficit = Math.addExact(ledger.insuranceDeficit(assetId), deficitUnits);
            long nextClearing = Math.addExact(ledger.clearingPnl(assetId), deficitUnits);
            ledger.setInsurance(assetId, nextInsurance, nextDeficit);
            ledger.setClearingPnl(assetId, nextClearing);
        }

    }

    /** 单笔强平命令槽的可复用续步：派发账户步骤，Owner 收集账户资金回执后派发账本，等账本完成才增加全局修订号。 */
    public static final class ExecutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 单笔命令复用的账户执行步骤，Lane 准备后由 Owner 等待其结果。 */
        private BatchExecutionStage stage;
        private ExecuteLiquidationCommand command;
        private Collection<CancellationOrder> canceledOrders;
        private long nextCursorOrderId;
        private RuntimeIdentityRegistry identities;
        /** done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。 */
        private boolean done;

        private long prepare(ExecuteLiquidationCommand command, Collection<CancellationOrder> canceledOrders,
                long nextCursorOrderId, TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
            initializeLedgerCommand(runtime);
            this.command = command; this.canceledOrders = canceledOrders;
            this.nextCursorOrderId = nextCursorOrderId; this.identities = identities;
            var route = runtime.liquidationRoute(command.liquidationId());
            long userId = route == null ? runtime.liquidation(command.liquidationId()).userId() : route.userId();
            this.done = false;
            return runtime.topology().accountLaneMask(userId);
        }

        /** 所属账户 Lane 创建或复用本笔执行步骤，应用撤单解冻或最终平仓，由步骤保存需要交账本的资金差额。 */
        @Override
        public Object apply(int ignored) {
            if (stage == null) stage = new BatchExecutionStage();
            stage.execute(command.liquidationId(), command.triggerPriceSequence(), command.executionPriceTicks(),
                    command.liquidationFeeRatePpm(), command.cursorOrderId(), canceledOrders, nextCursorOrderId, runtime, identities);
            return null;
        }

        /** Owner 等账户步骤完成，登记变更并预检查修订号增量，再同步应用账本并写最终修订号，重复轮询直接返回 true。 */
        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            if (stage.liquidated)
                runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
            Math.addExact(runtime.revision(), stage.canceledOrdersApplied ? 2 : 1);
            runtime.dispatchLedgerCommand(this);
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), stage.canceledOrdersApplied ? 2 : 1));
            done = true;
            return true;
        }

        /** 账本应用本笔强平非空的资金回执。 */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            stage.applyLedger(ledger);
        }

        /** 命令槽回收时释放本次命令、撤单集合和身份表引用，保留续步及账户结果供下一条命令复用。 */
        public void clearReferences() {
            command = null;
            canceledOrders = null;
            identities = null;
            nextCursorOrderId = 0;
            done = false;
        }
    }

    private static boolean executable(TradingAccountRuntime runtime, RiskScanner.LiquidationOrder liquidation,
                                      CoreInstrument instrument, long positionKey) {
        if (instrument == null || !RiskMarginCalculator.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        if (positionKey == 0) return false;
        Position position = runtime.position(positionKey);
        RiskMarginCalculator.Snapshot risk = runtime.riskSnapshot(positionKey);
        return position != null && position.instrument() == liquidation.instrument()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    /** 核对当前行情序号与强平计划一致，命令指定的触发序号和执行价也必须匹配。 */
    private static void validatePrice(TradingAccountRuntime runtime, RiskScanner.LiquidationOrder liquidation,
                                      long triggerPriceSequence, long executionPriceTicks) {
        MarkPrice mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || triggerPriceSequence > 0
                && triggerPriceSequence != liquidation.triggerPriceSequence()
                || executionPriceTicks != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    private static RiskScanner.LiquidationOrder copy(RiskScanner.LiquidationOrder current, long deficit, long priceTicks,
                                           long feeRatePpm, CoreLiquidationState.Status status, long feeUnits) {
        return new RiskScanner.LiquidationOrder(current.liquidationId(), current.userId(), current.symbolId(),
                current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                deficit, priceTicks, feeRatePpm, feeUnits, status, 0);
    }

    public static ResolutionWork beginResolution(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        var route = runtime.liquidationRoute(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
        CoreInstrument instrument = runtime.requireInstrument(route.instrumentId());
        int assetId = identities.assetId(instrument.settleAsset());
        ResolutionWork work = reuse == null || !reuse.reusable() ? new ResolutionWork() : reuse;
        work.sourceUserId = route.userId();
        work.prepare(runtime, command, assetId);
        work.prepareClaimReads(candidateIds, identities);
        return work;
    }

    /** 命令槽复用的强平补亏续步，按索赔读取、账本预算及记账、源账户更新的顺序推进。 */
    public static final class ResolutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private ResolveLiquidationCommand command;
        private RiskScanner.LiquidationOrder liquidation, nextLiquidation;
        /** 源合约结算资产整数编号，保险池、索赔和亏空必须使用同一资产。 */
        private int assetId;
        private boolean done, accountsDispatched;
        private long sourceUserId;
        private boolean capturingClaims;
        private long[] claimIds = new long[4];
        private int[] claimLanes = new int[4];
        private RiskScanner.LiquidationOrder[] claimReceipts = new RiskScanner.LiquidationOrder[4];
        private int claimCount;

        private void prepare(TradingAccountRuntime runtime, ResolveLiquidationCommand command, int assetId) {
            initializeLedgerCommand(runtime);
            runtime.requireRevisionCapacity();
            this.command = command;
            this.assetId = assetId;
            liquidation = nextLiquidation = null;
            done = accountsDispatched = capturingClaims = false;
        }

        private void prepareClaimReads(Iterable<Long> candidateIds, RuntimeIdentityRegistry identities) {
            claimCount = 0;
            long readMask = runtime.topology().accountLaneMask(sourceUserId);
            if (command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE) {
                for (long id : candidateIds) {
                    var route = runtime.liquidationRoute(id);
                    if (route == null) continue;
                    var instrument = runtime.instrument(route.instrumentId());
                    if (instrument == null || identities.assetId(instrument.settleAsset()) != assetId) continue;
                    if (claimCount == claimIds.length) {
                        int capacity = Math.multiplyExact(claimCount, 2);
                        claimIds = java.util.Arrays.copyOf(claimIds, capacity);
                        claimLanes = java.util.Arrays.copyOf(claimLanes, capacity);
                        claimReceipts = java.util.Arrays.copyOf(claimReceipts, capacity);
                    }
                    int laneId = runtime.topology().accountLaneId(route.userId());
                    claimIds[claimCount] = id; claimLanes[claimCount] = laneId; claimReceipts[claimCount++] = null;
                    readMask |= 1L << laneId;
                }
            }
            capturingClaims = true;
            runtime.dispatchControlLanes(readMask, this);
        }

        /** 账本校验强平阶段、确定分配顺序及期望覆盖额，只按可用保险余额覆盖亏空并生成新强平记录。 */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long nextDeficit = liquidation.deficitUnits();
            CoreLiquidationState.Status nextStatus;
            switch (command.resolution()) {
                case INSURANCE -> {
                    if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "insurance resolution requires insurance state");
                    if (command.coveredUnits() > liquidation.deficitUnits())
                        throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT", "insurance coverage must be within liquidation deficit");
                    long expectedCoverage = InsuranceAllocationPolicy.resolve(
                            claimReceipts, claimCount, liquidation.liquidationId(), assetId, ledger);
                    if (expectedCoverage < 0) throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                    if (command.coveredUnits() != expectedCoverage)
                        throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH", "insurance coverage does not match deterministic allocation");
                    if (command.coveredUnits() > ledger.insurance(assetId))
                        throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
                    nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                    nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
                    if (command.coveredUnits() != 0) {
                        ledger.setInsurance(assetId,
                                Math.subtractExact(ledger.insurance(assetId), command.coveredUnits()),
                                Math.subtractExact(ledger.insuranceDeficit(assetId), command.coveredUnits()));
                    }
                }
                case ADL -> throw new CoreStateRejectedException("INVALID_COMMAND", "ADL resolution requires atomic target deleveraging");
                case COMPLETED -> {
                    if (command.coveredUnits() != 0) throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                    if (liquidation.deficitUnits() != 0) throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                    nextStatus = CoreLiquidationState.Status.COMPLETED;
                }
                default -> throw new IllegalStateException("unknown liquidation resolution");
            }
            RiskScanner.LiquidationOrder current = liquidation;
            nextLiquidation = new RiskScanner.LiquidationOrder(current.liquidationId(), current.userId(), current.symbolId(),
                    current.marginMode(), current.positionSide(), current.instrument(), current.triggerPriceSequence(),
                    current.signedQuantitySteps(), current.closeQuantitySteps(), nextDeficit, current.executionPriceTicks(),
                    current.liquidationFeeRatePpm(), current.liquidationFeeUnits(), nextStatus, 0);
        }

        /** 账户 Lane 在读取阶段提供自己持有的源强平及待保险索赔回执。 */
        @Override public Object apply(int laneId) {
            if (capturingClaims) {
                if (runtime.currentLaneOwns(sourceUserId)) {
                    liquidation = runtime.liquidation(command.liquidationId());
                    if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
                }
                for (int index = 0; index < claimCount; index++) {
                    if (claimLanes[index] != laneId) continue;
                    var value = runtime.liquidation(claimIds[index]);
                    if (value != null && value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        claimReceipts[index] = value;
                }
                return null;
            }
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        }

        /** Owner 非阻塞等索赔读取、派发并等待账本，再派发源 Lane 的强平状态更新。 */
        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (capturingClaims) {
                if (!runtime.pollControlLanes()) return false;
                capturingClaims = false;
            }
            if (!accountsDispatched) {
                runtime.dispatchLedgerCommand(this);
                requireLedgerAccepted();
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(sourceUserId), this);
                accountsDispatched = true;
            }
            if (!runtime.pollControlLanes()) return false;
            finish();
            return true;
        }

        private void finish() {
            if (done) return;
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            java.util.Arrays.fill(claimReceipts, 0, claimCount, null);
            liquidation = nextLiquidation = null;
            command = null;
            done = true;
        }
    }

}
