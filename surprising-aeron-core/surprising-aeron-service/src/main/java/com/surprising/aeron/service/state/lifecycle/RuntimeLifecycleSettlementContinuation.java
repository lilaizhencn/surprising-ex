package com.surprising.aeron.service.state.lifecycle;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.ControlLaneDispatcher;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.ledger.SystemLedgerCommand;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 在线到期或固定价格结算的续步入口。Owner 校验命令后保存本页工作对象，按原控制派发器依次让账户 Lane 撤单、准备后值、应用账户，让 System Ledger 核对保险并记录财库及进度；Owner 通过 poll 收集完成状态。 构造约定：工具类不创建实例，工作状态保存在命令槽持有的 SettlementWork 中。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLifecycleSettlementContinuation {


    /**
     * 校验并新建当前结算页的续步对象，不在此等待账户结算完成。
     * @param command 本页结算编号、固定价格、期权参数及双游标
     * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
     * @param chunkCommandId 非空的本页命令 UUID
     * @param activeOrderIndex 币对活动订单路由索引
     * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
     * @param identities 币对与资产内部编号映射
     */
    public static SettlementWork prepare(SettleInstrumentCommand command,
                                              Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                              ActiveOrderIndex activeOrderIndex,
                                              TradingRuntimeState runtime,
                                              RuntimeIdentityRegistry identities) {
        return prepare(null, command, indexedUserIds, chunkCommandId, activeOrderIndex, runtime, identities);
    }

    /**
     * 校验结算编号、产品规则和已有游标，选取当前订单页；同一编号已经完整结算时返回完成对象，否则优先重用已经可回收的工作对象及其分组容量。此处只准备任务，账户写入随后由 poll 派发。
     * @param reuse 原命令槽持有的工作对象，仅 reusable 为真时重置使用，否则新建
     * @param command 本页结算编号、固定价格、期权参数及双游标
     * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
     * @param chunkCommandId 非空的本页命令 UUID
     * @param activeOrderIndex 币对活动订单路由索引
     * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
     * @param identities 币对与资产内部编号映射
     */
    public static SettlementWork prepare(SettlementWork reuse, SettleInstrumentCommand command,
                                              Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                              ActiveOrderIndex activeOrderIndex,
                                              TradingRuntimeState runtime,
                                              RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || chunkCommandId == null
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
        RuntimeLifecycleSettlement.validateProgress(previousProgress, command, true);
        // 此前是否已经完成全部账户侧撤单。
        boolean ordersComplete = previousProgress != null && previousProgress.ordersComplete();
        // 已有进度中的位置字段，当前全局分页仍固定返回零。
        int accountLaneId = previousProgress == null ? 0 : previousProgress.accountLaneId();
        // 本次选出的账户侧撤单路由。
        List<CancellationOrder> selectedOrders = List.of();
        // 订单页之后是否仍有后续记录。
        boolean moreOrders = false;
        if (!ordersComplete) {
            // 本次从活动订单或用户索引读取的页面。
            RuntimeLifecycleSettlement.OrderPage page = RuntimeLifecycleSettlement.selectOrders(runtime, identities, activeOrderIndex, instrument.instrumentId(),
                    accountLaneId, command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = !page.complete();
        }
        // 币对结算资产的内部编号。
        int assetId = identities.assetId(instrument.settleAsset());
        if (reuse == null || !reuse.reusable()) {
            return new SettlementWork(command, chunkCommandId, runtime,
                    instrument, kernel, symbolId, assetId, previousProgress, selectedOrders,
                    moreOrders, indexedUserIds);
        }
        return reuse.reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                previousProgress, selectedOrders, moreOrders, indexedUserIds);
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
            /** 先收齐撤单回执；订单读完后才派发用户结算后值计算。 */
            PREPARE,
            /** 收齐准备后值回执，随后转入账本保险核对，尚未应用账户。 */
            APPLY,
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
        /** 本页命令 UUID，保存结算进度时一并记录。 */
        private UUID chunkCommandId;
        /** 原权威运行时，Owner、账户 Lane 与账本按各自作用域访问。 */
        private TradingRuntimeState runtime;
        /** 本次结算使用的币对配置。 */
        private CoreInstrument instrument;
        /** 该币对产品的固定价格结算计算规则。 */
        private ProductTradingRules kernel;
        /**
         * symbolId：本次币对的内部编号。
         * assetId：本次结算资产的内部编号。
         */
        private int symbolId, assetId;
        /** 准备任务时读取的原进度，用于续页参数及进度位置。 */
        private TreasuryRuntime.LifecycleProgressRuntime previousProgress;
        /** 本页需要账户侧撤销的订单路由，原 Matcher 处理由外层流程负责。 */
        private List<CancellationOrder> selectedOrders;
        /** 本订单页后是否仍有活动订单，有则本页不结算持仓。 */
        private boolean moreOrders;
        /** 账本选出的本页全局用户列表，发布 usersReady 后供 Owner 和 Lane 读取。 */
        private List<Long> selectedUserIds;
        /** 等待账本消费的用户索引来源，完成选页后释放引用。 */
        private Iterable<Long> indexedUserIds;
        /** 用户页和分组已经准备好的跨线程发布标记，Owner 读取后才继续派发账户。 */
        private volatile boolean usersReady = true;
        /** 账本是否已接受选页任务，避免尚未完成时重复投递或回收。 */
        private boolean usersQueued;
        /** 复用的账本选页任务，只负责读取用户索引与发布路由回执。 */
        private final SystemLedgerLane.Entry userInput = /* 账本任务接口传入的财库参数；用户选页只用索引，不读取此参数。 */ ignored -> selectAccountUsers();
        /** 按实际账户所有权划分的用户原语列表，重置只清内容并保留容量。 */
        private final LongArrayList[] usersByLane;
        /** 账户侧撤单路由分组，具体列表按需分配并在后续页复用。 */
        private final ArrayList<CancellationOrder>[] ordersByLane;
        /** 全局用户索引本页是否已经读到末尾，不等于账户已应用。 */
        private boolean usersComplete;
        /** 本页含有待撤订单的账户 Lane 位图。 */
        private long orderLaneMask;
        /** 本页含有待检查用户的账户 Lane 位图。 */
        private long userLaneMask;
        /** Owner 下次 poll 应推进的阶段。 */
        private Phase phase;
        /** 已交给账户 Lane 的操作种类，与 Owner 正在等待的阶段分开保存。 */
        private Phase dispatchedPhase;
        /** 各 Lane 准备的账户后值列表，先供账本核对保险，再交回原 Lane 应用。 */
        private Object[] prepared;
        /** 最终阶段由账本合并的清算差额缓冲，重用时清空。 */
        private RuntimeTreasuryDelta treasuryDelta;
        /** 本页最终回执，包含是否仍需续页或补保险。 */
        private CoreSettlementProgressView result;
        /** 本次账本调用要执行的撤单进度、保险预算或最终记账阶段。 */
        private FinancialPhase financialPhase;
        /**
         * ledgerDispatched：当前财务阶段是否已成功投递，Owner 轮询时据此避免重复记账。
         * budgetSufficient：账本核对整页保险后的结果，完成回执发布后由 Owner 读取。
         */
        private boolean ledgerDispatched, budgetSufficient;
        /**
         * requiredInsurance：本页准备后值需要的保险总额，由账本精确汇总。
         * nextOrderCursor：撤单尚未全部结束时写入进度及结果的下一订单游标。
         */
        private long requiredInsurance, nextOrderCursor;
        /** 账户实际应用后返回的各 Lane 财库差额，账本最终阶段再合并。 */
        private RuntimeTreasuryDelta[] financialResults;

        /**
         * 按当前账户 Lane 数创建用户分组及订单分组入口，再初始化本页命令状态。
         * @param command 本页结算编号、固定价格、期权参数及双游标
         * @param chunkCommandId 非空的本页命令 UUID
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param instrument 已验证的结算币对配置
         * @param kernel 本产品结算计算规则
         * @param symbolId 币对内部编号
         * @param assetId 结算资产内部编号
         * @param previousProgress 本页开始前已保存的结算进度，首次开始为空
         * @param selectedOrders 本页要交给账户 Lane 撤销预留的订单路由
         * @param moreOrders 订单索引是否还有后续页
         * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
         */
        private SettlementWork(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            // 创建对象时固定的账户 Lane 数量。
            int laneCount = runtime.topology().accountLaneCount();
            this.usersByLane = new LongArrayList[laneCount];
            for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < laneCount; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = newLaneOrderGroups(laneCount);
            reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                    previousProgress, selectedOrders, moreOrders, indexedUserIds);
        }

        /**
         * 为已经完整完成的结算编号创建直接返回结果的对象；初始化继承的财务范围，但不派发账户任务。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        @SuppressWarnings("unchecked")
        private SettlementWork(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            initializeLedgerCommand(runtime);
            this.command = null; this.chunkCommandId = null;
            this.runtime = runtime; this.instrument = null; this.kernel = null;
            this.symbolId = this.assetId = 0; this.previousProgress = null; this.selectedOrders = List.of();
            this.moreOrders = false; this.selectedUserIds = List.of();
            this.usersByLane = new LongArrayList[runtime.topology().accountLaneCount()];
            for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = (ArrayList<CancellationOrder>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
            this.usersComplete = true;
            this.orderLaneMask = this.userLaneMask = 0; this.phase = Phase.DONE; this.result = result;
            this.dispatchedPhase = null;
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
         * @param command 本页结算编号、固定价格、期权参数及双游标
         * @param chunkCommandId 非空的本页命令 UUID
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param instrument 已验证的结算币对配置
         * @param kernel 本产品结算计算规则
         * @param symbolId 币对内部编号
         * @param assetId 结算资产内部编号
         * @param previousProgress 本页开始前已保存的结算进度，首次开始为空
         * @param selectedOrders 本页要交给账户 Lane 撤销预留的订单路由
         * @param moreOrders 订单索引是否还有后续页
         * @param indexedUserIds 按全局用户编号递增的持仓用户来源，由账本选页后再路由
         */
        private SettlementWork reset(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            if (usersQueued && !usersReady) throw new IllegalStateException("Ledger still owns settlement routes");
            initializeLedgerCommand(runtime);
            if (usersByLane.length != runtime.topology().accountLaneCount()) {
                throw new IllegalStateException("settlement continuation lane topology changed");
            }
            this.command = command; this.chunkCommandId = chunkCommandId; this.runtime = runtime;
            this.instrument = instrument; this.kernel = kernel; this.symbolId = symbolId; this.assetId = assetId;
            this.previousProgress = previousProgress; this.selectedOrders = selectedOrders;
            this.moreOrders = moreOrders; this.selectedUserIds = List.of();
            this.indexedUserIds = indexedUserIds;
            usersReady = usersQueued = false;
            this.usersComplete = false;
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
            if (prepared == null || prepared.length != usersByLane.length) prepared = new Object[usersByLane.length];
            else Arrays.fill(prepared, null);
            if (treasuryDelta == null) treasuryDelta = new RuntimeTreasuryDelta();
            else treasuryDelta.clear();
            if (financialResults == null) financialResults = new RuntimeTreasuryDelta[usersByLane.length];
            else Arrays.fill(financialResults, null);
            ledgerDispatched = budgetSufficient = false;
            requiredInsurance = nextOrderCursor = 0;
            financialPhase = null;
            this.result = null;
            this.dispatchedPhase = null;
            this.phase = selectedOrders.isEmpty() ? Phase.PREPARE : Phase.CANCEL;
            if (!runtime.systemLedgerStarted()) selectAccountUsers();
            return this;
        }

        /**
         * 由账本线程读取用户索引并按账户 Lane 分组；账本未激活时由初始化路径内联调用。订单仍有下一页时不选择持仓用户，最后发布 usersReady 并唤醒 Owner。
         */
        private void selectAccountUsers() {
            // 本次从活动订单或用户索引读取的页面。
            RuntimeLifecycleSettlement.UserPage page = moreOrders
                    ? new RuntimeLifecycleSettlement.UserPage(List.of(), 0, 0, true)
                    : RuntimeLifecycleSettlement.selectUsers(indexedUserIds, runtime, 0, command.cursorUserId(), command.maxUsers());
            selectedUserIds = page.userIds(); usersComplete = page.complete();
            for (long /* 本次账本选页得到的用户编号。 */ user : selectedUserIds) usersByLane[runtime.topology().accountLaneId(user)].add(user);
            userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            indexedUserIds = null;
            // 发布完成标记前保存的运行时引用，用于随后唤醒 Owner。
            TradingRuntimeState completion = runtime;
            usersReady = true;
            completion.signalOwnerCompletion();
        }

        /**
         * 返回本页实际交给账户 Lane 的同一份用户列表，避免结果路径再遍历索引；选页回执未发布时拒绝读取，工作对象重置或清理后不可继续借用旧页。
         */
        public List<Long> selectedUsers() {
            if (!usersReady) throw new IllegalStateException("Ledger settlement routes are incomplete");
            return selectedUserIds;
        }

        /**
         * 把可重用对象切换为已完成结果并清除用户、订单与准备后值；账本仍在选页时拒绝重置。这里只清理当前代码列出的缓冲，不声称清零所有财务字段。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        private SettlementWork resetCompleted(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            if (usersQueued && !usersReady) throw new IllegalStateException("Ledger still owns settlement routes");
            initializeLedgerCommand(runtime);
            this.command = null; this.chunkCommandId = null; this.runtime = runtime;
            this.instrument = null; this.kernel = null; this.symbolId = this.assetId = 0;
            this.previousProgress = null; this.selectedOrders = List.of(); this.selectedUserIds = List.of();
            this.moreOrders = false; this.usersComplete = true; this.orderLaneMask = this.userLaneMask = 0;
            indexedUserIds = null; usersReady = true; usersQueued = false;
            for (LongArrayList /* 当前 Lane 可复用的用户列表，重置或清理时只清内容。 */ users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> /* 当前 Lane 可复用的撤单列表，未分配时为空。 */ orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
            this.result = result; this.phase = Phase.DONE;
            this.dispatchedPhase = null;
            return this;
        }

        /**
         * 命令槽回收前清除当前命令、页面和准备后值引用，并保留分组容量；若账本仍拥有选页任务则本次直接返回。runtime 及部分财务回执字段仍由后续重置管理。
         */
        public void clearReferences() {
            if (usersQueued && !usersReady) return;
            indexedUserIds = null;
            command = null; chunkCommandId = null; instrument = null; kernel = null;
            previousProgress = null; selectedOrders = List.of(); selectedUserIds = List.of();
            result = null; phase = Phase.DONE;
            dispatchedPhase = null;
            for (LongArrayList /* 当前 Lane 可复用的用户列表，重置或清理时只清内容。 */ users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> /* 当前 Lane 可复用的撤单列表，未分配时为空。 */ orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
        }

        /**
         * 创建无需再推进账户或账本的已完成结算页。
         * @param runtime 保持原身份的权威运行时，元数据由 Owner 推进
         * @param result 无需再执行账户操作的结算完成回执
         */
        static SettlementWork completed(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            return new SettlementWork(runtime, result);
        }

        /**
         * Owner 分次推进本页：先等账本选页，派发撤单并收齐回执，再派发后值计算、核对整页保险、应用账户，最后等账本记录差额和进度。保险不足也会结束本次工作并返回未完成进度；返回 true 只表示这页工作有结果，不等于整个结算已完成。
         */
        public boolean poll() {
            runtime.assertOwner();
            if (phase == Phase.DONE) return true;
            if (!usersReady) {
                if (!usersQueued) usersQueued = runtime.dispatchLedgerQuery(userInput);
                if (!usersReady) return false;
            }
            switch (phase) {
                case CANCEL -> {
                    dispatchedPhase = Phase.CANCEL;
                    runtime.dispatchControlLanes(orderLaneMask, this);
                    phase = Phase.PREPARE;
                    return false;
                }
                case PREPARE -> {
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
                    dispatchedPhase = Phase.PREPARE;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY;
                    return false;
                }
                case APPLY -> {
                    if (!runtime.pollControlLanes()) return false;
                    Arrays.fill(prepared, null);
                    for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < usersByLane.length; lane++)
                        if ((userLaneMask & (1L << lane)) != 0) prepared[lane] = runtime.controlLaneResult(lane);
                    financialPhase = FinancialPhase.INSURANCE;
                    ledgerDispatched = false;
                    phase = Phase.BUDGET_WAIT;
                    return false;
                }
                case BUDGET_WAIT -> {
                    if (!pollLedger()) return false;
                    if (!budgetSufficient) {
                        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                        // 保险不足时原样保留的用户游标，不跳过尚未应用的用户页。
                        long cursor = command.cursorUserId();
                        result = new CoreSettlementProgressView(command.settlementId(), false, true,
                                0, cursor, selectedOrders.size(), 0, requiredInsurance);
                        phase = Phase.DONE;
                        return true;
                    }
                    dispatchedPhase = Phase.APPLY;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY_WAIT;
                    return false;
                }
                case APPLY_WAIT -> {
                    if (!runtime.pollControlLanes()) return false;
                    for (/* 当前分组数组的 Lane 下标。 */ int lane = 0; lane < usersByLane.length; lane++)
                        if ((userLaneMask & (1L << lane)) != 0)
                            financialResults[lane] = (RuntimeTreasuryDelta) runtime.controlLaneResult(lane);
                    financialPhase = FinancialPhase.FINAL;
                    ledgerDispatched = false;
                    phase = Phase.FINAL_WAIT;
                    return false;
                }
                case FINAL_WAIT -> {
                    if (!pollLedger()) return false;
                    runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), selectedOrders.isEmpty() ? 1 : 2));
                    if (financialPhase == FinancialPhase.ORDER_PROGRESS)
                        result = new CoreSettlementProgressView(command.settlementId(), false, false,
                                nextOrderCursor, 0, selectedOrders.size(), 0);
                    else {
                        // 本用户页已读到索引末尾或为空，从而可登记整个结算完成。
                        boolean complete = usersComplete || selectedUserIds.isEmpty();
                        result = new CoreSettlementProgressView(command.settlementId(), complete, true,
                                0, complete ? 0 : selectedUserIds.getLast(), selectedOrders.size(), selectedUserIds.size());
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
            return switch (dispatchedPhase) {
                case CANCEL -> {
                    RuntimeLifecycleSettlement.cancelOrdersOnLane(runtime, ordersByLane[lane]);
                    yield null;
                }
                case PREPARE -> RuntimeLifecycleSettlement.prepareLane(runtime, instrument, kernel, command,
                        usersByLane[lane], symbolId, assetId);
                case APPLY -> {
                    // 当前 Lane 准备的用户结算后值列表。
                    @SuppressWarnings("unchecked") List<RuntimeLifecycleSettlement.UserSettlement> plans =
                            (List<RuntimeLifecycleSettlement.UserSettlement>) prepared[lane];
                    yield RuntimeLifecycleSettlement.applyLane(runtime, assetId, plans);
                }
                default -> throw new IllegalStateException("settlement lane operation outside dispatch phase");
            };
        }

        /**
         * 预检元数据修订号容量，尝试把当前财务阶段交给账本；队列未接受或账本尚未完成时返回 false，已经接受后不重复投递。
         */
        private boolean pollLedger() {
            if (!ledgerDispatched) {
                Math.addExact(runtime.revision(), financialPhase == FinancialPhase.INSURANCE ? 1 : selectedOrders.isEmpty() ? 1 : 2);
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            return ledgerCommandCompleted();
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
                ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                        command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(),
                        false, previousProgress == null ? 0 : previousProgress.accountLaneId(), nextOrderCursor, 0, chunkCommandId));
                return;
            }
            if (financialPhase == FinancialPhase.INSURANCE) {
                requiredInsurance = 0;
                for (Object /* 某个 Lane 已完成准备阶段的通用回执。 */ receipt : prepared) {
                    if (receipt == null) continue;
                    // 当前 Lane 准备的用户结算后值列表。
                    @SuppressWarnings("unchecked") List<RuntimeLifecycleSettlement.UserSettlement> plans =
                            (List<RuntimeLifecycleSettlement.UserSettlement>) receipt;
                    for (RuntimeLifecycleSettlement.UserSettlement /* 当前用户结算后值及其保险需求。 */ plan : plans)
                        requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
                }
                budgetSufficient = requiredInsurance <= ledger.insurance(assetId);
                if (!budgetSufficient) {
                    // 保险不足时原样保留的用户游标，不跳过尚未应用的用户页。
                    long cursor = command.cursorUserId();
                    ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                            command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(),
                            true, 0, 0, cursor, chunkCommandId, requiredInsurance));
                } else if (requiredInsurance != 0) {
                    ledger.setInsurance(assetId, Math.subtractExact(ledger.insurance(assetId), requiredInsurance),
                            ledger.insuranceDeficit(assetId));
                }
                return;
            }
            treasuryDelta.clear();
            for (RuntimeTreasuryDelta /* 某个账户 Lane 实际应用后交来的清算差额。 */ delta : financialResults) if (delta != null) treasuryDelta.merge(delta);
            treasuryDelta.apply(ledger);
            // 本用户页已读到索引末尾或为空，从而可登记整个结算完成。
            boolean complete = usersComplete || selectedUserIds.isEmpty();
            if (complete) ledger.setLifecycleSettlement(symbolId, command.settlementId());
            else ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                    command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(), true,
                    previousProgress == null ? 0 : previousProgress.accountLaneId(), 0, selectedUserIds.getLast(), chunkCommandId));
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
