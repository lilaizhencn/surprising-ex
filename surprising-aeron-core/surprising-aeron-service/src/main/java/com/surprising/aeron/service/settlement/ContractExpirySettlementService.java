package com.surprising.aeron.service.settlement;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountExposureIndex;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 在线到期或固定价格结算的续步入口。Owner 校验命令后保存本页工作对象，按原控制派发器依次让账户 Lane 撤单、准备后值、应用账户，让 System Ledger 核对保险并记录财库及进度。Owner 通过 poll 收集完成状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContractExpirySettlementService {

    /** 校验结算编号、产品规则和已有游标，选取当前订单页。同一编号已经完整结算时返回完成对象，否则优先重用已经可回收的工作对象及其分组容量。 */
    public static SettlementWork prepare(SettlementWork reuse, SettleInstrumentCommand command, Iterable<Long> indexedUserIds,
            ActiveOrderIndex activeOrderIndex, AccountBook accountBook, IdIndex identities) {
        if (command == null || indexedUserIds == null
                || activeOrderIndex == null || accountBook == null || identities == null) {
            throw new IllegalArgumentException("invalid asynchronous runtime settlement");
        }
        CoreInstrument instrument = requireInstrument(accountBook, command);
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        long previousSettlement = accountBook.systemFunds().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            CoreSettlementProgressView completed = new CoreSettlementProgressView(
                    command.settlementId(), true, true, 0, 0, 0, 0);
            return reuse == null || !reuse.reusable() ? SettlementWork.completed(accountBook, completed)
                    : reuse.resetCompleted(accountBook, completed);
        }
        ProductTradingRules kernel = ProductTradingRules.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
        SystemFunds.LifecycleProgressRuntime previousProgress = accountBook.systemFunds().lifecycleProgress(symbolId);
        validateProgress(previousProgress, command);
        boolean ordersComplete = previousProgress != null && previousProgress.ordersComplete();
        List<CancellationOrder> selectedOrders = List.of();
        boolean moreOrders = false;
        if (!ordersComplete) {
            var page = activeOrderIndex.lifecycleOrders(0, instrument.instrumentId(), command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = page.nextCursorOrderId() != 0;
        }
        int assetId = identities.assetId(instrument.settleAsset());
        if (reuse == null || !reuse.reusable()) {
            return new SettlementWork(command, accountBook, instrument, kernel, symbolId, assetId, selectedOrders, moreOrders, indexedUserIds);
        }
        return reuse.reset(command, accountBook, instrument, kernel, symbolId, assetId, selectedOrders,
                moreOrders, indexedUserIds);
    }

    /** 由命令槽持有的一页在线结算工作。Owner 推进阶段，账户 Lane 只写自己的账户，账本只写财库与进度。对象和缓冲可以重用，使用前必须满足继承的回收条件，不能保留跨代页面引用。 */
    public static final class SettlementWork extends SystemLedgerCommand implements java.util.function.IntFunction<Object> {
        /** Owner 轮询所处的执行或等待位置。 */
        private enum Phase {
            /** 下一次派发本页账户侧撤单。 */
            CANCEL,
            /** 等待撤单回执。 */
            CANCEL_WAIT,
            /** 收齐准备后值回执，随后转入账本保险核对，尚未应用账户。 */
            PREPARE_WAIT,
            /** 等待账本保险结果，不足则结束本页，足够才派发账户应用。 */
            BUDGET_WAIT,
            /** 等待账户后值应用完毕并收集各 Lane 清算差额。 */
            APPLY_WAIT,
            /** 等待账本保存撤单进度或最终清算差额，再构造本页结果。 */
            FINAL_WAIT,
            /** 本次工作已经有结果或已被清理，仍需看结果判断整个结算是否完成。 */
            DONE
        }
        /** 同一工作对象当前交给账本执行的财务操作。 */
        private enum FinancialPhase {
            ORDER_PROGRESS,
            INSURANCE,
            FINAL
        }

        /** 当前页固定结算命令，Owner 初始化后供账户和账本阶段读取。 */
        private SettleInstrumentCommand command;
        private CoreInstrument instrument;
        private ProductTradingRules kernel;
        private int symbolId, assetId;
        private List<CancellationOrder> selectedOrders;
        private boolean moreOrders;
        private AccountExposureIndex.Page userPage;
        private Iterable<Long> indexedUserIds;
        private final LongArrayList[] usersByLane;
        private final ArrayList<CancellationOrder>[] ordersByLane;
        private long orderLaneMask;
        private long userLaneMask;
        /** Owner 当前等待或准备执行的阶段。 */
        private Phase phase;
        private List<UserSettlement>[] prepared;
        private CoreSettlementProgressView result;
        private FinancialPhase financialPhase;
        /** budgetSufficient：账本核对整页保险后的结果，完成回执发布后由 Owner 读取。 */
        private boolean budgetSufficient;
        private long requiredInsurance, nextOrderCursor;
        /** 各账户 Lane 实际应用后按原顺序累计的清算金额，账本最终阶段再合并。 */
        private long[] clearingByLane;

        private SettlementWork(SettleInstrumentCommand command,
                AccountBook accountBook, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            int laneCount = accountBook.topology().accountLaneCount();
            this.usersByLane = new LongArrayList[laneCount];
            for (int lane = 0; lane < laneCount; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = newLaneOrderGroups(laneCount);
            reset(command, accountBook, instrument, kernel, symbolId, assetId, selectedOrders, moreOrders, indexedUserIds);
        }

        @SuppressWarnings("unchecked")
        private SettlementWork(AccountBook accountBook, CoreSettlementProgressView result) {
            initializeLedgerCommand(accountBook);
            this.command = null;
            this.instrument = null; this.kernel = null;
            this.symbolId = this.assetId = 0; this.selectedOrders = List.of();
            this.moreOrders = false; this.userPage = null;
            this.usersByLane = new LongArrayList[accountBook.topology().accountLaneCount()];
            for (int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = (ArrayList<CancellationOrder>[]) new ArrayList<?>[accountBook.topology().accountLaneCount()];
            this.orderLaneMask = this.userLaneMask = 0; this.phase = Phase.DONE; this.result = result;
        }

        @SuppressWarnings("unchecked")
        private static ArrayList<CancellationOrder>[] newLaneOrderGroups(int laneCount) {
            return (ArrayList<CancellationOrder>[]) new ArrayList<?>[laneCount];
        }

        /** 账本未激活时直接选用户，否则延后交给账本线程选页。 */
        @SuppressWarnings("unchecked")
        private SettlementWork reset(SettleInstrumentCommand command, AccountBook accountBook,
                CoreInstrument instrument, ProductTradingRules kernel, int symbolId, int assetId,
                List<CancellationOrder> selectedOrders,
                boolean moreOrders, Iterable<Long> indexedUserIds) {
            initializeLedgerCommand(accountBook);
            if (usersByLane.length != accountBook.topology().accountLaneCount()) {
                throw new IllegalStateException("settlement continuation lane topology changed");
            }
            this.command = command;
            this.instrument = instrument; this.kernel = kernel; this.symbolId = symbolId; this.assetId = assetId;
            this.selectedOrders = selectedOrders;
            this.moreOrders = moreOrders; this.userPage = null;
            this.indexedUserIds = indexedUserIds;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            for (CancellationOrder order : selectedOrders) {
                if (order == null) continue;
                int lane = accountBook.topology().accountLaneId(order.userId());
                ArrayList<CancellationOrder> group = ordersByLane[lane];
                if (group == null) ordersByLane[lane] = group = new ArrayList<>();
                group.add(order);
            }
            this.orderLaneMask = laneMask(ordersByLane);
            this.userLaneMask = laneMask(usersByLane);
            if (prepared == null || prepared.length != usersByLane.length)
                prepared = (List<UserSettlement>[]) new List<?>[usersByLane.length];
            else Arrays.fill(prepared, null);
            if (clearingByLane == null) clearingByLane = new long[usersByLane.length];
            else Arrays.fill(clearingByLane, 0);
            budgetSufficient = false;
            requiredInsurance = nextOrderCursor = 0;
            financialPhase = null;
            this.result = null;
            this.phase = selectedOrders.isEmpty() ? Phase.CANCEL_WAIT : Phase.CANCEL;
            selectAccountUsers();
            return this;
        }

        private void selectAccountUsers() {
            userPage = moreOrders ? null
                    : AccountExposureIndex.Page.select(indexedUserIds, command.cursorUserId(), command.maxUsers());
            List<Long> selectedUserIds = userPage == null ? List.of() : userPage.userIds();
            for (long user : selectedUserIds) usersByLane[accountBook.topology().accountLaneId(user)].add(user);
            userLaneMask = laneMask(usersByLane);
            indexedUserIds = null;
        }

        /** 返回本页实际交给账户 Lane 的同一份用户列表，避免结果路径再遍历索引。工作对象重置或清理后不可继续借用旧页。 */
        public List<Long> selectedUsers() {
            return userPage == null ? List.of() : userPage.userIds();
        }

        private SettlementWork resetCompleted(AccountBook accountBook, CoreSettlementProgressView result) {
            initializeLedgerCommand(accountBook);
            this.command = null;
            this.instrument = null; this.kernel = null; this.symbolId = this.assetId = 0;
            this.selectedOrders = List.of(); this.userPage = null;
            this.moreOrders = false; this.orderLaneMask = this.userLaneMask = 0;
            indexedUserIds = null;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            this.result = result; this.phase = Phase.DONE;
            return this;
        }

        /** 命令槽回收前清除当前命令、页面和准备后值引用，并保留分组容量。 */
        public void clearReferences() {
            indexedUserIds = null;
            command = null;  instrument = null; kernel = null;
            selectedOrders = List.of(); userPage = null;
            result = null; phase = Phase.DONE;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
        }

        /** 创建无需再推进账户或账本的已完成结算页。 */
        static SettlementWork completed(AccountBook accountBook, CoreSettlementProgressView result) {
            return new SettlementWork(accountBook, result);
        }

        /** Owner 分次推进本页：派发撤单并收齐回执，再派发后值计算、核对整页保险、应用账户，最后等账本记录差额和进度。 */
        public boolean poll() {
            accountBook.assertOwner();
            if (phase == Phase.DONE) return true;
            switch (phase) {
                case CANCEL -> {
                    phase = Phase.CANCEL_WAIT;
                    accountBook.dispatchControlLanes(orderLaneMask, this);
                    return false;
                }
                case CANCEL_WAIT -> {
                    if (orderLaneMask != 0 && !accountBook.pollControlLanes()) return false;
                    if (moreOrders) {
                        nextOrderCursor = selectedOrders.isEmpty() ? command.cursorOrderId() : selectedOrders.getLast().orderId();
                        financialPhase = FinancialPhase.ORDER_PROGRESS;
                        phase = Phase.FINAL_WAIT;
                        return false;
                    }
                    if (userLaneMask == 0) {
                        financialPhase = FinancialPhase.FINAL;
                        phase = Phase.FINAL_WAIT;
                        return false;
                    }
                    phase = Phase.PREPARE_WAIT;
                    accountBook.dispatchControlLanes(userLaneMask, this);
                    return false;
                }
                case PREPARE_WAIT -> {
                    if (!accountBook.pollControlLanes()) return false;
                    financialPhase = FinancialPhase.INSURANCE;
                    phase = Phase.BUDGET_WAIT;
                    return false;
                }
                case BUDGET_WAIT -> {
                    applyFinancialPhase();
                    if (!budgetSufficient) {
                        accountBook.setMetadata(accountBook.productLine(), Math.incrementExact(accountBook.revision()));
                        long cursor = command.cursorUserId();
                        result = new CoreSettlementProgressView(command.settlementId(), false, true,
                                0, cursor, selectedOrders.size(), 0, requiredInsurance);
                        phase = Phase.DONE;
                        return true;
                    }
                    phase = Phase.APPLY_WAIT;
                    accountBook.dispatchControlLanes(userLaneMask, this);
                    return false;
                }
                case APPLY_WAIT -> {
                    if (!accountBook.pollControlLanes()) return false;
                    financialPhase = FinancialPhase.FINAL;
                    phase = Phase.FINAL_WAIT;
                    return false;
                }
                case FINAL_WAIT -> {
                    applyFinancialPhase();
                    accountBook.setMetadata(accountBook.productLine(), Math.addExact(accountBook.revision(), selectedOrders.isEmpty() ? 1 : 2));
                    if (financialPhase == FinancialPhase.ORDER_PROGRESS)
                        result = new CoreSettlementProgressView(command.settlementId(), false, false,
                                nextOrderCursor, 0, selectedOrders.size(), 0);
                    else {
                        boolean complete = userPage.complete() || userPage.userIds().isEmpty();
                        result = new CoreSettlementProgressView(command.settlementId(), complete, true,
                                0, complete ? 0 : userPage.nextCursorUserId(), selectedOrders.size(), userPage.userIds().size());
                    }
                    phase = Phase.DONE;
                    return true;
                }
                default -> throw new IllegalStateException("invalid settlement continuation phase");
            }
        }

        /** 由当前目标账户 Lane 按已派发阶段执行撤单、只读准备或应用后值。本实例复用作控制派发器的 Lane 函数，Owner 不在这里改账户。 */
        @Override
        public Object apply(int lane) {
            switch (phase) {
                case CANCEL_WAIT -> cancelOrdersOnLane(accountBook, ordersByLane[lane]);
                case PREPARE_WAIT -> prepared[lane] = prepareLane(accountBook, instrument, kernel, command,
                        usersByLane[lane], symbolId, assetId);
                case APPLY_WAIT -> clearingByLane[lane] = applyLane(accountBook, prepared[lane]);
                default -> throw new IllegalStateException("settlement lane operation outside dispatch phase");
            }
            return null;
        }

        private void applyFinancialPhase() {
            Math.addExact(accountBook.revision(), financialPhase == FinancialPhase.INSURANCE ? 1 : selectedOrders.isEmpty() ? 1 : 2);
            accountBook.dispatchLedgerCommand(this);
            requireLedgerAccepted();
        }

        /** 保险足够时保持原财务范围开放，以便之后账户应用和最终差额共用该范围。 */
        @Override protected boolean financialScopeComplete() {
            return financialPhase != FinancialPhase.INSURANCE || !budgetSufficient;
        }

        /** 在 System Ledger 上执行当前财务阶段：保存撤单进度、汇总并预扣保险，或合并账户回执后保存结算完成标记和用户游标。 */
        @Override protected void applyLedger(SystemFunds ledger) {
            if (financialPhase == FinancialPhase.ORDER_PROGRESS) {
                ledger.setLifecycleProgress(symbolId, new SystemFunds.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), false, nextOrderCursor, 0, 0));
                return;
            }
            if (financialPhase == FinancialPhase.INSURANCE) {
                requiredInsurance = 0;
                for (List<UserSettlement> plans : prepared) {
                    if (plans == null) continue;
                    for (UserSettlement plan : plans)
                        requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
                }
                budgetSufficient = requiredInsurance <= ledger.insurance(assetId);
                if (!budgetSufficient) {
                    long cursor = command.cursorUserId();
                    ledger.setLifecycleProgress(symbolId, new SystemFunds.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), true, 0, cursor, requiredInsurance));
                } else if (requiredInsurance != 0) {
                    ledger.setInsurance(assetId, Math.subtractExact(ledger.insurance(assetId), requiredInsurance),
                            ledger.insuranceDeficit(assetId));
                }
                return;
            }
            long clearing = 0;
            boolean accountsSettled = false;
            for (int lane = 0; lane < prepared.length; lane++) {
                if (prepared[lane] == null || prepared[lane].isEmpty()) continue;
                clearing = Math.addExact(clearing, clearingByLane[lane]);
                accountsSettled = true;
            }
            if (accountsSettled) ledger.setClearingPnl(assetId, Math.addExact(ledger.clearingPnl(assetId), clearing));
            boolean complete = userPage.complete() || userPage.userIds().isEmpty();
            if (complete) ledger.setLifecycleSettlement(symbolId, command.settlementId());
            else ledger.setLifecycleProgress(symbolId, new SystemFunds.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), true, 0, userPage.nextCursorUserId(), 0));
        }

        /** 返回本次工作已经发布的页结果。 */
        public CoreSettlementProgressView result() {
            if (result == null) throw new IllegalStateException("settlement has not completed");
            return result;
        }
    }

    /** 一个用户尚未应用的结算后值。键与持仓数组保留原容量，只能应用非空持仓槽。 */
    record UserSettlement(AccountBalance balance, long[] keys,
                          Position[] positions, long clearing, long insurance) { }

    /** 在所属账户 Lane 只计算本页用户的结算后值，不写余额和持仓。 */
    static List<UserSettlement> prepareLane(AccountBook accountBook, CoreInstrument instrument,
                                            ProductTradingRules kernel, SettleInstrumentCommand command,
                                            LongArrayList users, int symbolId, int assetId) {
        ArrayList<UserSettlement> plans = new ArrayList<>();
        for (int userIndex = 0; userIndex < users.size(); userIndex++) {
            long userId = users.get(userIndex);
            if (accountBook.user(userId) == null) continue;
            LongArrayList indexedKeys = accountBook.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (indexedKeys.isEmpty()) continue;
            long[] keys = new long[indexedKeys.size()];
            Position[] positions = new Position[keys.length];
            AccountBalance balance = accountBook.balance(userId, assetId);
            if (balance == null) throw new IllegalStateException("settlement balance is missing");
            long available = balance.availableUnits();
            long locked = balance.lockedUnits();
            long crossPnl = 0, crossMargin = 0, totalPnl = 0, insurance = 0;
            int index = 0;
            for (int positionIndex = 0; positionIndex < indexedKeys.size(); positionIndex++) {
                long key = indexedKeys.get(positionIndex);
                Position position = accountBook.position(key);
                if (position == null || position.signedQuantitySteps() == 0) continue;
                long pnl = kernel.lifecycleSettlementCashDeltaUnits(
                        instrument, position.signedQuantitySteps(), position.entryPriceTicks(),
                        command.settlementPriceTicks());
                totalPnl = Math.addExact(totalPnl, pnl);
                long margin = position.positionMarginUnits();
                locked = Math.subtractExact(locked, margin);
                if (position.marginMode() == CoreMarginMode.CROSS) {
                    crossPnl = Math.addExact(crossPnl, pnl);
                    crossMargin = Math.addExact(crossMargin, margin);
                } else {
                    long equity = Math.addExact(margin, pnl);
                    available = Math.addExact(available, Math.max(0, equity));
                    if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
                }
                keys[index] = key;
                positions[index++] = new Position(userId, symbolId, assetId, position.marginMode(),
                        position.positionSide(), position.instrument(), 0, 0, 0,
                        Math.addExact(position.realizedPnlUnits(), pnl), 0);
            }
            if (index == 0) continue;
            long equity = Math.addExact(Math.addExact(available, crossMargin), crossPnl);
            if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
            Math.incrementExact(accountBook.user(userId).revision());
            plans.add(new UserSettlement(
                    new AccountBalance(userId, assetId, Math.max(0, equity), locked),
                    keys, positions, Math.negateExact(totalPnl), insurance));
        }
        return plans;
    }

    /** 在所属账户 Lane 应用保险已确认的后值：替换有效持仓、替换余额并推进用户修订号，返回清算盈亏差额供财库应用。此步骤不再次检查保险，也不在这里实现跨 Lane 回滚。 */
    static long applyLane(AccountBook accountBook, List<UserSettlement> plans) {
        long clearing = 0;
        for (UserSettlement plan : plans) {
            for (int index = 0; index < plan.keys().length; index++) {
                if (plan.positions()[index] != null)
                    accountBook.replacePosition(plan.keys()[index], plan.positions()[index]);
            }
            accountBook.replaceBalance(plan.balance());
            accountBook.advanceUserRevision(plan.balance().userId());
            clearing = Math.addExact(clearing, plan.clearing());
        }
        return clearing;
    }

    /** 只处理当前 Lane 所属用户的订单。 */
    static void cancelOrdersOnLane(AccountBook accountBook, Collection<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (var order : orders) {
            if (!accountBook.currentLaneOwns(order.userId())) continue;
            OrderFundsReservation reservation = accountBook.reservation(order.orderId());
            if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
            accountBook.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    /** 把非空用户分组转换成参与 Lane 位图。 */
    static long laneMask(LongArrayList[] groups) {
        long mask = 0;
        for (int lane = 0; lane < groups.length; lane++) if (!groups[lane].isEmpty()) mask |= 1L << lane;
        return mask;
    }

    /** 把存在且非空的订单分组转换成参与 Lane 位图，未分配的分组直接跳过。 */
    static long laneMask(ArrayList<CancellationOrder>[] groups) {
        long mask = 0;
        for (int lane = 0; lane < groups.length; lane++) {
            if (groups[lane] != null && !groups[lane].isEmpty()) mask |= 1L << lane;
        }
        return mask;
    }

    /** 读取命令指定币对，缺失时返回业务拒绝。 */
    static CoreInstrument requireInstrument(AccountBook accountBook,
                                                 SettleInstrumentCommand command) {
        CoreInstrument instrument = accountBook.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    /** 首次分页必须从双零游标开始。已有进度时，结算编号、价格、撤单阶段与两个游标必须完全匹配，否则拒绝续页。 */
    static void validateProgress(SystemFunds.LifecycleProgressRuntime progress,
                                 SettleInstrumentCommand command) {
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
    }

}
