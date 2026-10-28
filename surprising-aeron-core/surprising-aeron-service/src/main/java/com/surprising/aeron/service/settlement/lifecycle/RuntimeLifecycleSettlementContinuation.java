package com.surprising.aeron.service.settlement.lifecycle;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.account.UserIndexPage;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 在线到期或固定价格结算的续步入口。Owner 校验命令后保存本页工作对象，按原控制派发器依次让账户 Lane 撤单、准备后值、应用账户，让 System Ledger 核对保险并记录财库及进度；Owner 通过 poll 收集完成状态。 构造约定：工具类不创建实例，工作状态保存在命令槽持有的 SettlementWork 中。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLifecycleSettlementContinuation {

    /**
     * 校验结算编号、产品规则和已有游标，选取当前订单页；同一编号已经完整结算时返回完成对象，否则优先重用已经可回收的工作对象及其分组容量。此处只准备任务，账户写入随后由 poll 派发。
     * @param reuse 原命令槽持有的工作对象，仅 reusable 为真时重置使用，否则新建
     * @param command 本页结算编号、固定价格及双游标
     * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
     * @param activeOrderIndex 币对活动订单路由索引
     * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
     * @param identities 币对与资产内部编号映射
     */
    public static SettlementWork prepare(SettlementWork reuse, SettleInstrumentCommand command, Iterable<Long> indexedUserIds,
            ActiveOrderIndex activeOrderIndex, TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null
                || activeOrderIndex == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid asynchronous runtime settlement");
        }
        // 命令指定且已经确认存在的币对配置。
        CoreInstrument instrument = RuntimeLifecycleSettlement.requireInstrument(runtime, command);
        // 币对内部编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 该币对已完整完成的最近结算编号。
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            // 同编号结算已完成时直接返回的空变化结果。
            CoreSettlementProgressView completed = new CoreSettlementProgressView(
                    command.settlementId(), true, true, 0, 0, 0, 0);
            return reuse == null || !reuse.reusable() ? SettlementWork.completed(runtime, completed)
                    : reuse.resetCompleted(runtime, completed);
        }
        // 用于校验结算请求的产品规则。
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
        // 用于核对双游标和结算参数的已有进度。
        TreasuryRuntime.LifecycleProgressRuntime previousProgress = runtime.treasury().lifecycleProgress(symbolId);
        RuntimeLifecycleSettlement.validateProgress(previousProgress, command);
        // 此前是否已经完成全部账户侧撤单。
        boolean ordersComplete = previousProgress != null && previousProgress.ordersComplete();
        // 本次选出的账户侧撤单路由。
        List<CancellationOrder> selectedOrders = List.of();
        // 订单页之后是否仍有后续记录。
        boolean moreOrders = false;
        if (!ordersComplete) {
            // 本次从活动订单或用户索引读取的页面。
            var page = activeOrderIndex.lifecycleOrders(0, instrument.instrumentId(), command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = page.nextCursorOrderId() != 0;
        }
        // 币对结算资产的内部编号。
        int assetId = identities.assetId(instrument.settleAsset());
        if (reuse == null || !reuse.reusable()) {
            return new SettlementWork(command, runtime, instrument, kernel, symbolId, assetId, selectedOrders, moreOrders, indexedUserIds);
        }
        return reuse.reset(command, runtime, instrument, kernel, symbolId, assetId, selectedOrders,
                moreOrders, indexedUserIds);
    }

    /**
     * 由命令槽持有的一页在线结算工作。Owner 推进阶段，账户 Lane 只写自己的账户，账本只写财库与进度；通过现有派发回执交接。对象和缓冲可以重用，使用前必须满足继承的回收条件，不能保留跨代页面引用。
     */
    public static final class SettlementWork extends SystemLedgerCommand implements java.util.function.IntFunction<Object> {
        /**
         * Owner 轮询所处的执行或等待位置；它描述本页内部推进，不是新的业务协议状态。
         */
        private enum Phase {
            /** 下一次派发本页账户侧撤单。 */
            CANCEL,
            /** 等待撤单回执；无订单时直接从这里准备账户结算。 */
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
        /**
         * 同一工作对象当前交给账本执行的财务操作。
         */
        private enum FinancialPhase {
            /** 只保存撤单尚未结束的下一订单游标。 */
            ORDER_PROGRESS,
            /** 汇总整页保险需求；足够时预扣，不足时保存原用户游标。 */
            INSURANCE,
            /** 合并账户清算差额并保存用户续页进度或完整结算编号。 */
            FINAL
        }

        /** 当前页固定结算命令，Owner 初始化后供账户和账本阶段读取。 */
        private SettleInstrumentCommand command;
        /** 本次结算使用的币对配置。 */
        private CoreInstrument instrument;
        /** 该币对产品的固定价格结算计算规则。 */
        private ProductTradingRules kernel;
        /**
         * symbolId：本次币对的内部编号。
         * assetId：本次结算资产的内部编号。
         */
        private int symbolId, assetId;
        /** 本页需要账户侧撤销的订单路由，原 Matcher 处理由外层流程负责。 */
        private List<CancellationOrder> selectedOrders;
        /** 本订单页后是否仍有活动订单，有则本页不结算持仓。 */
        private boolean moreOrders;
        /** 账本选出的全局用户页及续页信息；本页仅撤单或已完成时为空。 */
        private UserIndexPage userPage;
        /** 等待消费的用户索引来源，完成选页后释放引用。 */
        private Iterable<Long> indexedUserIds;
        /** 按实际账户所有权划分的用户原语列表，重置只清内容并保留容量。 */
        private final LongArrayList[] usersByLane;
        /** 账户侧撤单路由分组，具体列表按需分配并在后续页复用。 */
        private final ArrayList<CancellationOrder>[] ordersByLane;
        /** 本页含有待撤订单的账户 Lane 位图。 */
        private long orderLaneMask;
        /** 本页含有待检查用户的账户 Lane 位图。 */
        private long userLaneMask;
        /** Owner 当前等待或准备执行的阶段；账户回调读取同一等待阶段，收齐回执后才推进。 */
        private Phase phase;
        /** 各 Lane 准备的账户后值列表，先供账本核对保险，再交回原 Lane 应用。 */
        private List<RuntimeLifecycleSettlement.UserSettlement>[] prepared;
        /** 本页最终回执，包含是否仍需续页或补保险。 */
        private CoreSettlementProgressView result;
        /** 本次账本调用要执行的撤单进度、保险预算或最终记账阶段。 */
        private FinancialPhase financialPhase;
        /**
         * budgetSufficient：账本核对整页保险后的结果，完成回执发布后由 Owner 读取。
         */
        private boolean budgetSufficient;
        /**
         * requiredInsurance：本页准备后值需要的保险总额，由账本精确汇总。
         * nextOrderCursor：撤单尚未全部结束时写入进度及结果的下一订单游标。
         */
        private long requiredInsurance, nextOrderCursor;
        /** 各账户 Lane 实际应用后按原顺序累计的清算金额，账本最终阶段再合并。 */
        private long[] clearingByLane;

        /**
         * 按当前账户 Lane 数创建用户分组及订单分组入口，再初始化本页命令状态。
         * @param command 本页结算编号、固定价格及双游标
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param instrument 已验证的结算币对配置
         * @param kernel 本产品结算计算规则
         * @param symbolId 币对内部编号
         * @param assetId 结算资产内部编号
         * @param selectedOrders 本页要交给账户 Lane 撤销预留的订单路由
         * @param moreOrders 订单索引是否还有后续页
         * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
         */
        private SettlementWork(SettleInstrumentCommand command,
                TradingAccountRuntime runtime, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            // 创建对象时固定的账户 Lane 数量。
            int laneCount = runtime.topology().accountLaneCount();
            this.usersByLane = new LongArrayList[laneCount];
            for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < laneCount; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = newLaneOrderGroups(laneCount);
            reset(command, runtime, instrument, kernel, symbolId, assetId, selectedOrders, moreOrders, indexedUserIds);
        }

        /**
         * 为已经完整完成的结算编号创建直接返回结果的对象；初始化继承的财务范围，但不派发账户任务。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        @SuppressWarnings("unchecked")
        private SettlementWork(TradingAccountRuntime runtime, CoreSettlementProgressView result) {
            initializeLedgerCommand(runtime);
            this.command = null;
            this.instrument = null; this.kernel = null;
            this.symbolId = this.assetId = 0; this.selectedOrders = List.of();
            this.moreOrders = false; this.userPage = null;
            this.usersByLane = new LongArrayList[runtime.topology().accountLaneCount()];
            for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = (ArrayList<CancellationOrder>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
            this.orderLaneMask = this.userLaneMask = 0; this.phase = Phase.DONE; this.result = result;
        }

        /**
         * 创建按 Lane 排列的订单分组数组，具体列表等实际有订单时再分配。
         * @param laneCount 本运行时账户 Lane 总数
         */
        @SuppressWarnings("unchecked")
        private static ArrayList<CancellationOrder>[] newLaneOrderGroups(int laneCount) {
            return (ArrayList<CancellationOrder>[]) new ArrayList<?>[laneCount];
        }

        /**
         * 重新装入当前页，保留并清空原分组容量、账户回执及财库差额；尚有账本选页任务时拒绝重置，Lane 数改变也拒绝。账本未激活时直接选用户，否则延后交给账本线程选页。
         * @param command 本页结算编号、固定价格及双游标
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param instrument 已验证的结算币对配置
         * @param kernel 本产品结算计算规则
         * @param symbolId 币对内部编号
         * @param assetId 结算资产内部编号
         * @param selectedOrders 本页要交给账户 Lane 撤销预留的订单路由
         * @param moreOrders 订单索引是否还有后续页
         * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
         */
        @SuppressWarnings("unchecked")
        private SettlementWork reset(SettleInstrumentCommand command, TradingAccountRuntime runtime,
                CoreInstrument instrument, ProductTradingRules kernel, int symbolId, int assetId,
                List<CancellationOrder> selectedOrders,
                boolean moreOrders, Iterable<Long> indexedUserIds) {
            initializeLedgerCommand(runtime);
            if (usersByLane.length != runtime.topology().accountLaneCount()) {
                throw new IllegalStateException("settlement continuation lane topology changed");
            }
            this.command = command;
            this.instrument = instrument; this.kernel = kernel; this.symbolId = symbolId; this.assetId = assetId;
            this.selectedOrders = selectedOrders;
            this.moreOrders = moreOrders; this.userPage = null;
            this.indexedUserIds = indexedUserIds;
            for (LongArrayList /* 当前 Lane 可复用的用户列表，重置或清理时只清内容。 */ users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> /* 当前 Lane 可复用的撤单列表，未分配时为空。 */ orders : ordersByLane) if (orders != null) orders.clear();
            for (CancellationOrder /* 本页要按用户路由归组的撤单记录。 */ order : selectedOrders) {
                if (order == null) continue;
                // 当前分组数组的 Lane 下标。
                int lane = runtime.topology().accountLaneId(order.userId());
                // 该订单所属 Lane 的撤单列表。
                ArrayList<CancellationOrder> group = ordersByLane[lane];
                if (group == null) ordersByLane[lane] = group = new ArrayList<>();
                group.add(order);
            }
            this.orderLaneMask = RuntimeLifecycleSettlement.laneMask(ordersByLane);
            this.userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            if (prepared == null || prepared.length != usersByLane.length)
                prepared = (List<RuntimeLifecycleSettlement.UserSettlement>[]) new List<?>[usersByLane.length];
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

        /**
         * 读取用户索引并按账户 Lane 分组；订单仍有下一页时不选择持仓用户。
         */
        private void selectAccountUsers() {
            userPage = moreOrders ? null
                    : UserIndexPage.select(indexedUserIds, command.cursorUserId(), command.maxUsers());
            List<Long> selectedUserIds = userPage == null ? List.of() : userPage.userIds();
            for (long /* 本次账本选页得到的用户编号。 */ user : selectedUserIds) usersByLane[runtime.topology().accountLaneId(user)].add(user);
            userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            indexedUserIds = null;
        }

        /**
         * 返回本页实际交给账户 Lane 的同一份用户列表，避免结果路径再遍历索引；工作对象重置或清理后不可继续借用旧页。
         */
        public List<Long> selectedUsers() {
            return userPage == null ? List.of() : userPage.userIds();
        }

        /**
         * 把可重用对象切换为已完成结果并清除用户、订单与准备后值。这里只清理当前代码列出的缓冲，不声称清零所有财务字段。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        private SettlementWork resetCompleted(TradingAccountRuntime runtime, CoreSettlementProgressView result) {
            initializeLedgerCommand(runtime);
            this.command = null;
            this.instrument = null; this.kernel = null; this.symbolId = this.assetId = 0;
            this.selectedOrders = List.of(); this.userPage = null;
            this.moreOrders = false; this.orderLaneMask = this.userLaneMask = 0;
            indexedUserIds = null;
            for (LongArrayList /* 当前 Lane 可复用的用户列表，重置或清理时只清内容。 */ users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> /* 当前 Lane 可复用的撤单列表，未分配时为空。 */ orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            this.result = result; this.phase = Phase.DONE;
            return this;
        }

        /**
         * 命令槽回收前清除当前命令、页面和准备后值引用，并保留分组容量。runtime 及部分财务回执字段仍由后续重置管理。
         */
        public void clearReferences() {
            indexedUserIds = null;
            command = null;  instrument = null; kernel = null;
            selectedOrders = List.of(); userPage = null;
            result = null; phase = Phase.DONE;
            for (LongArrayList /* 当前 Lane 可复用的用户列表，重置或清理时只清内容。 */ users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> /* 当前 Lane 可复用的撤单列表，未分配时为空。 */ orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
        }

        /**
         * 创建无需再推进账户或账本的已完成结算页。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        static SettlementWork completed(TradingAccountRuntime runtime, CoreSettlementProgressView result) {
            return new SettlementWork(runtime, result);
        }

        /**
         * Owner 分次推进本页：派发撤单并收齐回执，再派发后值计算、核对整页保险、应用账户，最后等账本记录差额和进度。保险不足也会结束本次工作并返回未完成进度；返回 true 只表示这页工作有结果，不等于整个结算已完成。
         */
        public boolean poll() {
            runtime.assertOwner();
            if (phase == Phase.DONE) return true;
            switch (phase) {
                case CANCEL -> {
                    phase = Phase.CANCEL_WAIT;
                    runtime.dispatchControlLanes(orderLaneMask, this);
                    return false;
                }
                case CANCEL_WAIT -> {
                    if (orderLaneMask != 0 && !runtime.pollControlLanes()) return false;
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
                    runtime.dispatchControlLanes(userLaneMask, this);
                    return false;
                }
                case PREPARE_WAIT -> {
                    if (!runtime.pollControlLanes()) return false;
                    financialPhase = FinancialPhase.INSURANCE;
                    phase = Phase.BUDGET_WAIT;
                    return false;
                }
                case BUDGET_WAIT -> {
                    applyFinancialPhase();
                    if (!budgetSufficient) {
                        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                        // 保险不足时原样保留的用户游标，不跳过尚未应用的用户页。
                        long cursor = command.cursorUserId();
                        result = new CoreSettlementProgressView(command.settlementId(), false, true,
                                0, cursor, selectedOrders.size(), 0, requiredInsurance);
                        phase = Phase.DONE;
                        return true;
                    }
                    phase = Phase.APPLY_WAIT;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    return false;
                }
                case APPLY_WAIT -> {
                    if (!runtime.pollControlLanes()) return false;
                    financialPhase = FinancialPhase.FINAL;
                    phase = Phase.FINAL_WAIT;
                    return false;
                }
                case FINAL_WAIT -> {
                    applyFinancialPhase();
                    runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), selectedOrders.isEmpty() ? 1 : 2));
                    if (financialPhase == FinancialPhase.ORDER_PROGRESS)
                        result = new CoreSettlementProgressView(command.settlementId(), false, false,
                                nextOrderCursor, 0, selectedOrders.size(), 0);
                    else {
                        // 本用户页已读到索引末尾或为空，从而可登记整个结算完成。
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

        /**
         * 由当前目标账户 Lane 按已派发阶段执行撤单、只读准备或应用后值；本实例复用作控制派发器的 Lane 函数，Owner 不在这里改账户。
         * @param lane 控制派发器当前指定的账户 Lane 编号
         */
        @Override
        public Object apply(int lane) {
            switch (phase) {
                case CANCEL_WAIT -> RuntimeLifecycleSettlement.cancelOrdersOnLane(runtime, ordersByLane[lane]);
                case PREPARE_WAIT -> prepared[lane] = RuntimeLifecycleSettlement.prepareLane(runtime, instrument, kernel, command,
                        usersByLane[lane], symbolId, assetId);
                case APPLY_WAIT -> clearingByLane[lane] = RuntimeLifecycleSettlement.applyLane(runtime, prepared[lane]);
                default -> throw new IllegalStateException("settlement lane operation outside dispatch phase");
            }
            return null;
        }

        /**
         * 预检元数据修订号容量，直接把当前财务阶段交给账本内联执行。
         */
        private void applyFinancialPhase() {
            Math.addExact(runtime.revision(), financialPhase == FinancialPhase.INSURANCE ? 1 : selectedOrders.isEmpty() ? 1 : 2);
            runtime.dispatchLedgerCommand(this);
            requireLedgerAccepted();
        }

        /**
         * 保险足够时保持原财务范围开放，以便之后账户应用和最终差额共用该范围；订单进度、保险不足或最终阶段才结束该范围。
         */
        @Override protected boolean financialScopeComplete() {
            return financialPhase != FinancialPhase.INSURANCE || !budgetSufficient;
        }

        /**
         * 在 System Ledger 上执行当前财务阶段：保存撤单进度、汇总并预扣保险，或合并账户回执后保存结算完成标记和用户游标。保险不足时保留原用户游标，绝不在本阶段应用账户后值。
         * @param ledger 只由当前账本线程写入的原 TreasuryRuntime
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (financialPhase == FinancialPhase.ORDER_PROGRESS) {
                ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), false, nextOrderCursor, 0, 0));
                return;
            }
            if (financialPhase == FinancialPhase.INSURANCE) {
                requiredInsurance = 0;
                for (List<RuntimeLifecycleSettlement.UserSettlement> plans : prepared) {
                    if (plans == null) continue;
                    for (RuntimeLifecycleSettlement.UserSettlement /* 当前用户结算后值及其保险需求。 */ plan : plans)
                        requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
                }
                budgetSufficient = requiredInsurance <= ledger.insurance(assetId);
                if (!budgetSufficient) {
                    // 保险不足时原样保留的用户游标，不跳过尚未应用的用户页。
                    long cursor = command.cursorUserId();
                    ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), true, 0, cursor, requiredInsurance));
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
            // 非空计划的清算金额可以为零；空计划不产生账本写入。
            if (accountsSettled) ledger.setClearingPnl(assetId, Math.addExact(ledger.clearingPnl(assetId), clearing));
            // 本用户页已读到索引末尾或为空，从而可登记整个结算完成。
            boolean complete = userPage.complete() || userPage.userIds().isEmpty();
            if (complete) ledger.setLifecycleSettlement(symbolId, command.settlementId());
            else ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), command.settlementPriceTicks(), true, 0, userPage.nextCursorUserId(), 0));
        }

        /**
         * 返回本次工作已经发布的页结果；尚未得到结果时拒绝读取，仍须检查结果内 complete 才知道整笔结算是否结束。
         */
        public CoreSettlementProgressView result() {
            if (result == null) throw new IllegalStateException("settlement has not completed");
            return result;
        }
    }

}
