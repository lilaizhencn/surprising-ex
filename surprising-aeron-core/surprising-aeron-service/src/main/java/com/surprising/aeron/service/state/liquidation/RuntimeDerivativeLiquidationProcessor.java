package com.surprising.aeron.service.state.liquidation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import com.surprising.aeron.service.state.risk.CoreRiskPolicy;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.ledger.SystemLedgerCommand;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.math.*;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import java.util.Collection;
import java.util.List;

/** 强平步骤 3–6：校验执行条件，等待 Matcher 撤单，再完成账户和账本交接。 衍生品强平的账户执行与账本交接。前置检查由所属 Lane 确认持仓仍需强平，随后在该 Lane 应用已撤订单回执、解冻并平仓；收付、实收强平费及未覆盖亏空形成差额交唯一账本，Owner 等两边完成后提交修订号。 构造约定：只提供强平执行步骤，不额外保存账户权威状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeDerivativeLiquidationProcessor {


    /**
     * 核对命令前业务状态与运行时的产品线和修订号，再进入同步强平执行。
     * @param before 本命令执行前的业务状态，用来检查产品线和运行时修订号一致
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static TradingRuntimeState applyExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CancellationOrder> canceledOrders,
                                                     TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    /**
     * 在账本线程尚未启动的同步运行环境执行最终强平账户步骤；账本已启动时要求改用异步续步。
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static TradingRuntimeState applyExecutionRuntime(ExecuteLiquidationCommand command,
                                                            Collection<CancellationOrder> canceledOrders,
                                                            TradingRuntimeState runtime,
                                                            RuntimeIdentityRegistry identities) {
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active liquidation requires its asynchronous continuation");
        execute(null, command, canceledOrders, 0, runtime, identities, false);
        return runtime;
    }

    /**
     * 新建或复用已经可回收的单笔强平续步，保留校验及账户步骤对象供命令槽复用，并派发目标账户 Lane。
     * @param reuse 命令槽上一次可复用的单笔执行续步，null 或尚不可回收时重新创建
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static ExecutionWork beginExecution(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CancellationOrder> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return execute(reuse, command, canceledOrders, nextCursorOrderId, runtime, identities, true);
    }

    /**
     * Matcher 撤单前的账户前置检查，随命令槽复用数组。Owner 准备动作路由，所属 Lane 读取强平、持仓和风险事实并写结果字节；Owner 收到完成位后才可选择要撤单的动作，回收时释放本次引用。
     */
    public static final class ExecutionCheck implements java.util.function.IntFunction<Object> {
        /**
         * CHECK_LANE：动作已有路由，仍待所属账户 Lane 检查。
         * EXECUTABLE：账户及价格事实仍符合本动作要求，可以继续执行。
         * OBSOLETE：没有有效路由或计划、持仓已不适用，跳过该动作。
         * STALE_PRICE：触发价格序号或执行标记价已过期，消费本动作结果时可据此拒绝。
         */
        private static final byte CHECK_LANE = 0, EXECUTABLE = 1, OBSOLETE = 2, STALE_PRICE = 3;
        /** 各检查动作从所属 Lane 捕获的强平记录引用，检查结束前不供 Owner 消费。 */
        private LiquidationRuntime[] liquidations = new LiquidationRuntime[4];
        /** 各检查动作对应的合约配置引用，随本批输入一并回收。 */
        private CoreInstrument[] instruments = new CoreInstrument[4];
        /** 各动作由所属 Lane 定位的目标持仓整数键，0 表示没有有效持仓。 */
        private long[] positionKeys = new long[4];
        /** 各动作归属的账户 Lane 编号，-1 表示没有有效源账户路由。 */
        private int[] laneIds = new int[4];
        /**
         * liquidationIds：与动作同下标的强平编号数组。
         * priceSequences：与动作同下标的命令触发价格序号数组。
         * prices：与动作同下标的命令执行价 tick 数组。
         * cursors：与动作同下标的命令撤单游标数组。
         */
        private long[] liquidationIds = new long[4], priceSequences = new long[4], prices = new long[4], cursors = new long[4];
        /** 本命令共用的业务编号与运行时整数身份映射。 */
        private RuntimeIdentityRegistry identities;
        /** 各动作的检查结果字节，Lane 写入后通过既有控制完成位发布给 Owner。 */
        private byte[] results = new byte[4];
        /** 本次步骤或续步所绑定的交易运行时。 */
        private TradingRuntimeState runtime;
        /** 本批前置检查的有效动作数量，只有这个数组前缀参与校验和清理。 */
        private int count;
        /** 本批前置检查涉及的账户 Lane 位集合，Owner 据此只派发相关 Lane。 */
        private long laneMask;
        /**
         * dispatched：检查任务是否已经交给相关 Lane，避免 Owner 重复派发。
         * done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。
         */
        private boolean dispatched, done;

        /**
         * 为单笔强平初始化一个检查槽，保存其强平编号、触发价格、执行价和撤单游标，再定位需要读取的 Lane。
         * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        public void prepare(ExecuteLiquidationCommand command, TradingRuntimeState runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, 1);
            prepareAction(0, command.liquidationId(), command.triggerPriceSequence(),
                    command.executionPriceTicks(), command.cursorOrderId(), identities);
        }

        /**
         * 按批命令动作数量准备检查槽，逐项保存输入与 Lane 路由，让一批动作共用一次账户检查派发。
         * @param command 本次多个强平动作组成的批命令，每项分别保存检查结果
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        public void prepare(ExecuteLiquidationBatchCommand command, TradingRuntimeState runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, command.actions().size());
            for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < count; index++) {
                // 本批命令当前动作，逐项保存它的强平、价格和游标输入。
                var action = command.actions().get(index);
                prepareAction(index, action.liquidationId(), action.triggerPriceSequence(),
                        action.executionPriceTicks(), action.cursorOrderId(), identities);
            }
        }

        /**
         * Owner 确认旧输入已释放，按需一起扩容所有并行数组，再清空本次参与 Lane 和完成标记，避免把两条命令混在一起。
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param count 本命令需要校验的强平动作数量，用作并行数组的有效长度
         */
        private void initialize(TradingRuntimeState runtime, int count) {
            runtime.assertOwner();
            if (this.runtime != null) throw new IllegalStateException("liquidation checks already prepared");
            if (count > results.length) {
                // 检查动作超出原数组容量时使用的新容量，至少翻倍，所有并行数组一起替换。
                int capacity = Math.max(count, Math.multiplyExact(results.length, 2));
                liquidations = new LiquidationRuntime[capacity]; instruments = new CoreInstrument[capacity];
                positionKeys = new long[capacity]; laneIds = new int[capacity]; results = new byte[capacity];
                liquidationIds = new long[capacity]; priceSequences = new long[capacity]; prices = new long[capacity]; cursors = new long[capacity];
            }
            this.runtime = runtime; this.count = count; laneMask = 0; dispatched = done = false;
        }

        /**
         * 保存一个动作输入，先标为失效；找到源用户后再标为待 Lane 检查并并入 Lane 位集合，同步运行环境可直接定位尚无路由的强平记录。
         * @param index 本批动作在检查或结果数组中的下标，必须在有效数量内
         * @param liquidationId 当前待校验动作的强平编号
         * @param priceSequence 本动作声明的触发行情序号，正值时必须与强平计划一致
         * @param executionPriceTicks 本动作要求的执行价格 tick，必须与当前标记价一致
         * @param cursor 本动作声明的当前撤单游标，必须与计划阶段或已保存继续位置一致
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        private void prepareAction(int index, long liquidationId, long priceSequence,
                                   long executionPriceTicks, long cursor, RuntimeIdentityRegistry identities) {
            this.identities = identities;
            liquidationIds[index] = liquidationId; priceSequences[index] = priceSequence;
            prices[index] = executionPriceTicks; cursors[index] = cursor;
            results[index] = OBSOLETE; laneIds[index] = -1;
            // Owner 持有的源强平路由，用它确定源用户和账户 Lane。
            var route = runtime.liquidationRoute(liquidationId);
            // 从强平路由或同步强平记录得到的源用户编号，没有记录时为 0。
            long userId = route == null ? 0 : route.userId();
            if (route == null && !runtime.asynchronousCommands()) {
                // 同步环境未找到路由时直接读取的强平正文，仅用于确定源用户。
                var value = runtime.liquidation(liquidationId);
                if (value != null) userId = value.userId();
            }
            if (userId == 0) return;
            laneIds[index] = runtime.topology().accountLaneId(userId);
            results[index] = CHECK_LANE; laneMask |= 1L << laneIds[index];
        }

        /**
         * 判断当前是否已绑定一条待检查命令，绑定后必须完成或清理引用才能再次准备。
         */
        public boolean prepared() { return runtime != null; }

        /**
         * Owner 只派发一次相关 Lane 并非阻塞等待结果；没有任何有效路由时直接完成，结果未就绪则返回 false。
         */
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

        /**
         * 所属 Lane 逐项核对强平阶段和撤单游标，再检查标记价、产品可强平性、持仓和风险；分别写可执行、已失效或价格过期结果，游标冲突直接拒绝。
         * @param laneId 正在检查的账户 Lane 编号，只处理归属该 Lane 的动作
         */
        @Override public Object apply(int laneId) {
            for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < count; index++) {
                if (laneIds[index] != laneId || results[index] != CHECK_LANE) continue;
                // 当前 Lane 上读到的强平记录，缺失或阶段不再可执行时把动作标为失效。
                LiquidationRuntime value = runtime.liquidation(liquidationIds[index]);
                liquidations[index] = value;
                if (value == null || (value.status() != CoreLiquidationState.Status.PLANNED
                        && value.status() != CoreLiquidationState.Status.ORDERED)) {
                    results[index] = OBSOLETE; continue;
                }
                if (value.status() == CoreLiquidationState.Status.PLANNED && cursors[index] != 0
                        || value.status() == CoreLiquidationState.Status.ORDERED && cursors[index] != value.nextCancelOrderId())
                    throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT", "liquidation cursor does not match state");
                // 强平目标币对当前行情，触发序号和执行价都必须与它一致。
                MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
                if (mark == null || mark.priceSequence() != value.triggerPriceSequence()
                        || priceSequences[index] > 0 && priceSequences[index] != value.triggerPriceSequence()
                        || prices[index] != mark.markPriceTicks()) {
                    results[index] = STALE_PRICE; continue;
                }
                // 当前源强平合约配置，最终执行时用于风险、持仓和结算资产核对。
                CoreInstrument instrument = runtime.instrument(identities.instrumentId(value.symbolId()));
                instruments[index] = instrument;
                if (instrument == null || !CoreRiskPolicy.canLiquidate(instrument.contractType(), value.signedQuantitySteps())) {
                    results[index] = OBSOLETE; continue;
                }
                positionKeys[index] = identities.findPositionKeyValueInLane(runtime.currentLaneCommandScope(), value.userId(), instrument, value.positionSide());
                results[index] = positionKeys[index] != 0 && RuntimeDerivativeLiquidationProcessor.executable(
                        runtime, value, instrument, positionKeys[index]) ? EXECUTABLE : OBSOLETE;
            }
            return null;
        }

        /**
         * 检查结果已完成后取得该动作捕获的强平记录；失效动作可能没有读到记录并返回 null。
         * @param index 本批动作在检查或结果数组中的下标，必须在有效数量内
         */
        public LiquidationRuntime liquidation(int index) { requireResult(index); return liquidations[index]; }

        /**
         * 检查完成后只判断该动作是否可执行撤单；价格过期或失效动作返回 false，便于同批筛掉而不影响其他账户。
         * @param index 本批动作在检查或结果数组中的下标，必须在有效数量内
         */
        public boolean cancelsOrders(int index) { requireResult(index); return results[index] == EXECUTABLE; }

        /**
         * 检查完成后判断动作是否可执行；若因标记价过期而不可执行，则在本动作消费时返回 STALE_MARK_PRICE 业务拒绝。
         * @param index 本批动作在检查或结果数组中的下标，必须在有效数量内
         */
        public boolean executable(int index) {
            requireResult(index);
            if (results[index] == STALE_PRICE)
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
            return results[index] == EXECUTABLE;
        }

        /**
         * 要求全部 Lane 检查已经完成且动作下标有效，避免提前读取尚未发布的数组结果。
         * @param index 本批动作在检查或结果数组中的下标，必须在有效数量内
         */
        private void requireResult(int index) {
            if (!done || index < 0 || index >= count)
                throw new IllegalStateException("liquidation Lane check is not complete");
        }

        /**
         * 释放本批强平和配置引用并清空参与标记，保留各数组容量供下次命令槽使用。
         */
        public void clearReferences() {
            java.util.Arrays.fill(liquidations, 0, count, null);
            java.util.Arrays.fill(instruments, 0, count, null);
            runtime = null; identities = null; count = 0; laneMask = 0; dispatched = done = false;
        }
    }

    /**
     * 批量执行中的一笔账户步骤，保存强平命令、Matcher 已完成的撤单回执及下一撤单游标；构造器检查命令存在且继续游标非负。
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
     */
    public record ExecutionRequest(ExecuteLiquidationCommand command,
                                   Collection<CancellationOrder> canceledOrders,
                                   long nextCursorOrderId) {
        /**
         * 要求批量账户步骤有具体命令，下一撤单游标不能为负，0 表示本次可以进入最终执行。
         */
        public ExecutionRequest {
            if (command == null || nextCursorOrderId < 0) {
                throw new IllegalArgumentException("invalid liquidation batch request");
            }
        }
    }

    /**
     * Owner 根据各动作源用户合并 Lane 路由，一次派发多个账户执行步骤；每个 Lane 只准备并修改自己的账户，返回统一的账本交接续步。
     * @param requests 本批要一起派发的强平账户步骤列表，每项有自己的撤单回执和继续游标
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static java.util.function.BooleanSupplier beginExecutionBatch(
            List<ExecutionRequest> requests, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (requests == null || requests.isEmpty() || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid liquidation execution batch");
        }
        runtime.assertOwner();
        // 本批按请求下标保存的账户执行步骤数组，由各所属 Lane 填充。
        BatchExecutionStage[] stages = new BatchExecutionStage[requests.size()];
        // 当前批量账户步骤涉及的 Lane 位集合，合并后一次派发。
        long laneMask = 0;
        // 与请求同下标的源账户 Lane 编号，Lane 回调据此跳过其他用户。
        int[] laneIds = new int[requests.size()];
        for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < requests.size(); index++) {
            // Owner 持有的源强平路由，用它确定源用户和账户 Lane。
            var route = runtime.liquidationRoute(requests.get(index).command().liquidationId());
            if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
            laneIds[index] = runtime.topology().accountLaneId(route.userId());
            laneMask |= 1L << laneIds[index];
        }
        // 各请求账户阶段返回的资金差额数组，最后一并交账本续步。
        RuntimeTreasuryDelta[] results = new RuntimeTreasuryDelta[stages.length];
        runtime.dispatchControlLanes(laneMask, /* 当前批量回调正在执行的账户 Lane，只准备和应用属于自己的步骤。 */ lane -> {
            for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < stages.length; index++) {
                if (laneIds[index] != lane) continue;
                stages[index] = BatchExecutionStage.prepare(requests.get(index), runtime, identities);
                results[index] = stages[index].apply();
            }
            return results;
        });
        return new BatchFinancialWork(runtime, stages, results);
    }

    /**
     * 一批独立强平账户步骤的资金交接续步。各 Lane 各写自己的结果，Owner 收集后把资金差额交账本逐项应用，账本完成后才一次提交本批全局修订号。
     */
    private static final class BatchFinancialWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier {
        /** 本次步骤或续步所绑定的交易运行时。 */
        private final TradingRuntimeState runtime;
        /** 本批各请求的账户执行步骤，所属 Lane 完成后 Owner 读取其用户与修订信息。 */
        private final BatchExecutionStage[] stages;
        /** 本批账户资金回执数组，Owner 收集完成后由账本按原请求顺序应用。 */
        private final RuntimeTreasuryDelta[] results;
        /**
         * accountsCollected：Owner 是否已经收集并登记账户阶段结果，防止重复登记变更。
         * ledgerDispatched：资金账本命令是否已经成功入队，防止重复记账。
         * done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。
         */
        private boolean accountsCollected, ledgerDispatched, done;
        /** 本批各步骤应增加的全局修订号合计，提交前先检查整数范围。 */
        private long revisionDelta;

        /**
         * 绑定这批账户步骤和资金结果数组，初始化一次账本命令，供 Owner 后续轮询完成。
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param stages 与请求同下标的账户执行步骤，由所属 Lane 准备并写入
         * @param results 与账户步骤同下标的资金结果数组，账户阶段完成后供 Owner 和账本读取
         */
        private BatchFinancialWork(TradingRuntimeState runtime, BatchExecutionStage[] stages,
                                   RuntimeTreasuryDelta[] results) {
            initializeLedgerCommand(runtime);
            this.runtime = runtime; this.stages = stages; this.results = results;
        }

        /**
         * Owner 先等全部账户步骤，登记有资金结果的用户并累计修订号增量；账本入队并完成后才写入本批最终修订号，等待期间返回 false。
         */
        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (!accountsCollected) {
                if (!runtime.pollControlLanes()) return false;
                for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < stages.length; index++) {
                    // Owner 正在收集的一笔账户步骤，用它定位结算变化并累计修订号增量。
                    BatchExecutionStage stage = stages[index];
                    if (results[index] != null)
                        runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
                    revisionDelta = Math.addExact(revisionDelta, stage.canceledCount == 0 ? 1 : 2);
                }
                Math.addExact(runtime.revision(), revisionDelta);
                accountsCollected = true;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), revisionDelta));
            done = true;
            return true;
        }

        /**
         * 账本按请求数组顺序应用各非空资金差额，单纯撤单游标推进或失效计划没有额外资金回执。
         * @param ledger 当前账本线程持有的资金总账，强平差额在此应用
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            for (RuntimeTreasuryDelta /* 账本本轮要应用的一个账户资金差额，空回执不产生资金记账。 */ delta : results) if (delta != null) delta.apply(ledger);
        }
    }

    /**
     * 一笔强平在所属账户 Lane 内的执行步骤，保存本批撤单编号、当前持仓及币对输入；按阶段选择只推进撤单游标，或在最后一批真正平仓结算。
     */
    private static final class BatchExecutionStage {
        /** 本笔强平命令，提供执行价格、费率和撤单游标等固定输入。 */
        private ExecuteLiquidationCommand command;
        /** Matcher 已撤且本笔尚待账户解冻的订单编号数组。 */
        private long[] orderIds;
        /** 本批后下一笔待撤编号，0 表示可进入最终平仓。 */
        private long nextCursorOrderId;
        /** 本笔实际需要应用的撤单数量，失效计划会把它清零。 */
        private int canceledCount;
        /** 只推进撤单的阶段标记，true 时保存下一游标，不执行持仓平仓。 */
        private boolean advance;
        /** 本次步骤或续步所绑定的交易运行时。 */
        private TradingRuntimeState runtime;
        /** 本账户步骤读取的源强平记录，后续检查及执行以它为输入。 */
        private LiquidationRuntime liquidation;
        /** 源强平合约的配置引用，最终执行时用于持仓检查和金额计算。 */
        private CoreInstrument instrument;
        /** 源持仓的结算资产整数编号，用于现金修改及登记结算变更。 */
        private int assetId;
        /** 源用户当前持仓腿的运行时整数键。 */
        private long positionKey;
        /** 源用户所属的账户 Lane 编号，账户工作只在该 Lane 执行。 */
        private int laneId;
        /** 这笔强平所属的源用户编号，按它定位 Lane 和发布变更。 */
        private long userId;

        /**
         * 创建当前 Lane 的一笔账户执行步骤，并按命令、撤单列表及持仓输入初始化阶段字段。
         * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
         * @param orderIds 本批已在 Matcher 撤销、尚待账户解冻的订单编号数组，无撤单时为空
         * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
         * @param advance 是否只完成本批撤单并保存下一游标，true 时暂不执行持仓强平
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param liquidation 源账户当前强平记录，提供用户、币对、持仓腿和计划平仓量
         * @param instrument 目标合约配置，最终强平时用于验证产品和计算金额，只推进撤单时可为空
         * @param assetId 本笔账户步骤的结算资产整数编号，仅最终持仓结算时使用
         * @param positionKey 源强平目标持仓腿的运行时整数键，0 表示尚未定位到有效持仓
         */
        private BatchExecutionStage(ExecuteLiquidationCommand command, long[] orderIds,
                                    long nextCursorOrderId, boolean advance,
                                    TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                    CoreInstrument instrument, int assetId, long positionKey) {
            reset(command, orderIds, nextCursorOrderId, advance, runtime, liquidation,
                    instrument, assetId, positionKey);
        }

        /**
         * 复用账户步骤时替换全部输入，记录本次撤单数量，并从强平源用户确定账户 Lane；不在这里实际撤单或平仓。
         * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
         * @param orderIds 本批已在 Matcher 撤销、尚待账户解冻的订单编号数组，无撤单时为空
         * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
         * @param advance 是否只完成本批撤单并保存下一游标，true 时暂不执行持仓强平
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param liquidation 源账户当前强平记录，提供用户、币对、持仓腿和计划平仓量
         * @param instrument 目标合约配置，最终强平时用于验证产品和计算金额，只推进撤单时可为空
         * @param assetId 本笔账户步骤的结算资产整数编号，仅最终持仓结算时使用
         * @param positionKey 源强平目标持仓腿的运行时整数键，0 表示尚未定位到有效持仓
         */
        private void reset(ExecuteLiquidationCommand command, long[] orderIds,
                           long nextCursorOrderId, boolean advance,
                           TradingRuntimeState runtime, LiquidationRuntime liquidation,
                           CoreInstrument instrument, int assetId, long positionKey) {
            this.command = command; this.orderIds = orderIds; this.nextCursorOrderId = nextCursorOrderId;
            this.canceledCount = orderIds == null ? 0 : orderIds.length;
            this.advance = advance; this.runtime = runtime;
            this.liquidation = liquidation; this.instrument = instrument; this.assetId = assetId;
            this.positionKey = positionKey; this.userId = liquidation.userId();
            this.laneId = runtime.topology().accountLaneId(userId);
        }

        /**
         * 从批量请求取出命令、撤单回执和下一游标，进入完整的账户输入检查。
         * @param request 当前批量步骤的命令、撤单回执和下一游标组合
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        private static BatchExecutionStage prepare(ExecutionRequest request,
                                                    TradingRuntimeState runtime,
                                                    RuntimeIdentityRegistry identities) {
            return prepare(null, request.command(), request.canceledOrders(), request.nextCursorOrderId(),
                    runtime, identities);
        }

        /**
         * 在当前账户范围读取强平，按仅推进撤单或最终执行分别检查游标、阶段与价格；把撤单回执转成编号数组并核对所属用户，再定位持仓键与结算资产，创建或复用执行步骤。
         * @param reuse 上次已经结束的账户执行步骤，可为空以创建新对象
         * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
         * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
         * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        private static BatchExecutionStage prepare(BatchExecutionStage reuse,
                                                    ExecuteLiquidationCommand command,
                                                    Collection<CancellationOrder> canceledOrders,
                                                    long nextCursorOrderId,
                                                    TradingRuntimeState runtime,
                                                    RuntimeIdentityRegistry identities) {
            if (!runtime.productLine().isDerivative()) {
                throw new IllegalArgumentException("liquidation requires derivative product");
            }
            // 当前账户 Lane 读取的源强平记录，缺失时拒绝本笔执行。
            LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
            if (liquidation == null) {
                throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
            }
            // 下一撤单游标非零表示仍在分批撤单，当前步骤不进入最终持仓结算。
            boolean advance = nextCursorOrderId != 0;
            if (advance) {
                if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                        && liquidation.nextCancelOrderId() != command.cursorOrderId()) {
                    throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                            "liquidation cancellation cursor does not match state");
                }
            } else {
                if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                        && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
                }
                validatePrice(runtime, liquidation, command);
            }
            // 本笔 Matcher 撤单回执数量，用于分配并填充订单编号数组。
            int count = canceledOrders == null ? 0 : canceledOrders.size();
            // 本笔待应用账户解冻的撤单编号数组，没有撤单时留空。
            long[] orderIds = count == 0 ? null : new long[count];
            if (count != 0) {
                // 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。
                int index = 0;
                for (var /* 当前 Matcher 撤单回执，必须属于源强平同一用户才能装入编号数组。 */ order : canceledOrders) {
                    if (order == null || order.userId() != liquidation.userId()) {
                        throw new IllegalArgumentException("liquidation cancellation must belong to its account");
                    }
                    orderIds[index++] = order.orderId();
                }
            }
            // 当前源强平合约配置，最终执行时用于风险、持仓和结算资产核对。
            CoreInstrument instrument = advance ? null : runtime.instrument(identities.instrumentId(liquidation.symbolId()));
            // 当前账户命令作用域，存在时只能通过它查找本 Lane 持仓身份和资产。
            AccountLaneState scoped = runtime.currentLaneCommandScope();
            // 按源用户、合约和持仓腿定位的整数键，缺失时为 0。
            long positionKey = instrument == null ? 0 : scoped == null
                    ? identities.findPositionKeyValue(liquidation.userId(), instrument, liquidation.positionSide())
                    : identities.findPositionKeyValueInLane(scoped, liquidation.userId(), instrument, liquidation.positionSide());
            // 当前 Lane 中定位到的源持仓，用它取得结算资产并核对配置。
            PositionRuntime position = scoped == null || positionKey == 0 ? null : scoped.position(positionKey);
            // 最终执行使用的结算资产编号，只有有效合约和持仓时才有实际值。
            int assetId = instrument == null ? 0 : scoped == null ? identities.assetId(instrument.settleAsset())
                    : position == null ? 0 : position.assetId();
            if (position != null && !identities.asset(assetId).equals(instrument.settleAsset()))
                throw new IllegalStateException("liquidation position settlement asset differs from instrument");
            if (reuse == null) return new BatchExecutionStage(command, orderIds, nextCursorOrderId, advance,
                    runtime, liquidation, instrument, assetId, positionKey);
            reuse.reset(command, orderIds, nextCursorOrderId, advance, runtime, liquidation,
                    instrument, assetId, positionKey);
            return reuse;
        }

        /**
         * 最终执行前先判断计划是否已经失效；失效则取消计划且不应用撤单回执。有效时逐笔撤单解冻，尚有下一游标则保留 ORDERED，最后一批才真正执行持仓强平。
         */
        private RuntimeTreasuryDelta apply() {
            // 本笔已经到最终阶段，但当前持仓或风险不再符合强平计划，因此不应应用撤单或平仓。
            boolean obsolete = !advance && !executable(runtime, liquidation, instrument, positionKey);
            if (obsolete) canceledCount = 0;
            for (/* 当前处理的动作或账户步骤数组下标，只访问归属本 Lane 或本批的有效项。 */ int index = 0; index < canceledCount; index++) {
                // 本轮已在 Matcher 撤销、需要在账户中确认取消并解冻的订单编号。
                long orderId = orderIds[index];
                // 源用户该订单的当前状态，必须仍有效且有未成交量才能应用账户撤单。
                OrderRuntime order = runtime.order(orderId);
                if (order == null || order.userId() != liquidation.userId() || order.canceled()
                        || order.remainingQuantitySteps() == 0) {
                    throw new IllegalArgumentException("runtime liquidation cancellation requires open orders");
                }
                // 对应订单的资金冻结记录，撤单时释放其全部当前预占金额。
                ReservationRuntime reservation = runtime.reservation(orderId);
                if (reservation == null) {
                    throw new IllegalStateException("runtime liquidation reservation is missing: " + orderId);
                }
                runtime.cancelOrder(orderId, liquidation.userId(), reservation.reservedUnits());
            }
            if (obsolete) {
                runtime.replaceLiquidation(copy(liquidation, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
                runtime.advanceUserRevision(liquidation.userId());
                return null;
            }
            if (advance) {
                runtime.replaceLiquidation(new LiquidationRuntime(liquidation.liquidationId(), liquidation.userId(),
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(), liquidation.instrument(),
                        liquidation.triggerPriceSequence(),
                        liquidation.signedQuantitySteps(), liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), CoreLiquidationState.Status.ORDERED, nextCursorOrderId));
                return null;
            }
            return executeAccountLiquidation(command, runtime, liquidation, instrument, assetId, positionKey);
        }

        /**
         * 命令槽回收时释放命令、撤单数组、运行时和币对输入引用，保留步骤对象供下一次使用。
         */
        private void clearReferences() {
            command = null;
            orderIds = null;
            runtime = null;
            liquidation = null;
            instrument = null;
        }
    }

    /**
     * Owner 校验衍生品输入，准备可复用的单笔续步；异步时派发所属 Lane 后返回，同步时释放 Owner 账户访问权、执行账户变更并应用完成回执。
     * @param reuse 命令槽上一次可复用的单笔执行续步，null 或尚不可回收时重新创建
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     * @param asynchronous 是否仅派发账户步骤并返回续步，交给 Owner 后续轮询账本完成
     */
    private static ExecutionWork execute(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CancellationOrder> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean asynchronous) {
        if (command == null || runtime == null || identities == null || nextCursorOrderId < 0
                || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation account stage");
        }
        runtime.assertOwner();
        // 当前命令新建或复用的单笔强平续步，准备后派发源账户 Lane。
        ExecutionWork work = reuse == null || !reuse.reusable() ? new ExecutionWork() : reuse;
        work.prepare(command, canceledOrders, nextCursorOrderId, runtime, identities);
        if (asynchronous) {
            runtime.dispatchControlLanes(1L << work.laneId, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        // 同步账户执行返回的各 Lane 结果，按本笔 Lane 下标取资金回执。
        Object[] results = runtime.executeLaneMutations(1L << work.laneId, 1, work);
        work.finish(results[work.laneId]);
        return null;
    }

    /**
     * 单笔强平命令槽的可复用续步：派发账户步骤，Owner 收集账户资金回执后派发账本，等账本完成才增加全局修订号。
     */
    public static final class ExecutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 单笔命令复用的账户执行步骤，Lane 准备后由 Owner 等待其结果。 */
        private BatchExecutionStage stage;
        /** 本次步骤或续步所绑定的交易运行时。 */
        private TradingRuntimeState runtime;
        /** 本笔强平命令，提供执行价格、费率和撤单游标等固定输入。 */
        private ExecuteLiquidationCommand command;
        /** 本次从 Matcher 收到的撤单回执，命令槽回收时释放集合引用。 */
        private Collection<CancellationOrder> canceledOrders;
        /** 本批后下一笔待撤编号，0 表示可进入最终平仓。 */
        private long nextCursorOrderId;
        /** 本命令共用的业务编号与运行时整数身份映射。 */
        private RuntimeIdentityRegistry identities;
        /** 源用户所属的账户 Lane 编号，账户工作只在该 Lane 执行。 */
        private int laneId;
        /**
         * done：本阶段已经完成的标记，Owner 重复轮询时可直接结束。
         * accountsCollected：Owner 是否已经收集并登记账户阶段结果，防止重复登记变更。
         * ledgerDispatched：资金账本命令是否已经成功入队，防止重复记账。
         */
        private boolean done, accountsCollected, ledgerDispatched;
        /** Owner 从账户 Lane 收到的资金差额，账本完成前保持有效引用。 */
        private RuntimeTreasuryDelta treasuryDelta;

        /**
         * 绑定本次命令、撤单回执及下一游标，通过源强平路由定位 Lane，重置账户和账本完成标记并清掉旧资金回执。
         * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
         * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
         * @param nextCursorOrderId 本批后仍待撤的下一订单编号，0 表示已经到最终强平执行阶段
         * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
         * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
         */
        private void prepare(ExecuteLiquidationCommand command, Collection<CancellationOrder> canceledOrders,
                long nextCursorOrderId, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
            initializeLedgerCommand(runtime);
            this.command = command; this.canceledOrders = canceledOrders;
            this.nextCursorOrderId = nextCursorOrderId; this.identities = identities;
            // Owner 持有的源强平路由，用它确定源用户和账户 Lane。
            var route = runtime.liquidationRoute(command.liquidationId());
            // 从强平路由或同步强平记录得到的源用户编号，没有记录时为 0。
            long userId = route == null ? runtime.liquidation(command.liquidationId()).userId() : route.userId();
            laneId = runtime.topology().accountLaneId(userId);
            this.runtime = runtime;
            this.done = accountsCollected = ledgerDispatched = false;
            treasuryDelta = null;
        }

        /**
         * 所属账户 Lane 创建或复用本笔执行步骤，应用撤单解冻或最终平仓，并返回需要交账本的资金差额。
         * @param ignored 统一账户回调签名中的 Lane 参数；当前步骤已在准备阶段绑定所属 Lane
         */
        @Override
        public Object apply(int ignored) {
            stage = BatchExecutionStage.prepare(stage, command, canceledOrders, nextCursorOrderId, runtime, identities);
            return stage.apply();
        }

        /**
         * Owner 等账户步骤完成，登记变更并预检查修订号增量，再派发和等待账本；两边完成后写最终修订号，重复轮询直接返回 true。
         */
        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!accountsCollected) {
                if (!runtime.pollControlLanes()) return false;
                treasuryDelta = (RuntimeTreasuryDelta) runtime.controlLaneResult(stage.laneId);
                if (treasuryDelta != null)
                    runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
                Math.addExact(runtime.revision(), stage.canceledCount == 0 ? 1 : 2);
                accountsCollected = true;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), stage.canceledCount == 0 ? 1 : 2));
            done = true;
            return true;
        }

        /**
         * 账本应用本笔强平非空的资金回执；仅推进撤单游标或计划失效时没有资金差额。
         * @param ledger 当前账本线程持有的资金总账，强平差额在此应用
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (treasuryDelta != null) treasuryDelta.apply(ledger);
        }

        /**
         * 同步路径把账户回执交给统一完成步骤，登记账户变化、应用账本差额并推进全局修订号，避免重复完成。
         * @param result 同步账户步骤返回的资金差额回执，可能为空以表示没有最终现金结算
         */
        private void finish(Object result) {
            if (done) return;
            finishExecution(runtime, stage.userId, stage.assetId, stage.positionKey,
                    (RuntimeTreasuryDelta) result, stage.canceledCount);
            done = true;
        }

        /**
         * 命令槽回收时释放本次输入和内部账户步骤引用，保留续步及步骤对象供下一条命令复用。
         */
        public void clearReferences() {
            if (stage != null) stage.clearReferences();
            runtime = null;
            command = null;
            canceledOrders = null;
            identities = null;
            nextCursorOrderId = 0;
            done = false;
        }
    }

    /**
     * 同步完成时，有资金差额才登记账户结算变化并记总账；不论是否有资金回执，都按本笔是否含撤单推进相应全局修订号。
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param userId 这笔强平所属的源账户用户编号
     * @param assetId 本笔账户步骤的结算资产整数编号，仅最终持仓结算时使用
     * @param positionKey 源强平目标持仓腿的运行时整数键，0 表示尚未定位到有效持仓
     * @param treasuryDelta 本笔强平需要应用到保险、亏空及清算总账的资金差额，可为空
     * @param canceledCount 本笔实际应用的撤单数量，用于决定全局修订号增加一还是二
     */
    private static void finishExecution(TradingRuntimeState runtime, long userId, int assetId,
            long positionKey, RuntimeTreasuryDelta treasuryDelta, int canceledCount) {
        if (treasuryDelta != null) {
            runtime.recordUserSettlementChanges(userId, assetId, positionKey);
            treasuryDelta.apply(runtime.treasury());
        }
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), canceledCount == 0 ? 1 : 2));
    }

    /**
     * 所属 Lane 按计划平仓量分摊保证金，计算期货盈亏或期权现金市值及强平费，按全仓逐仓边界应用现金，再更新余额、剩余持仓和亏空状态，返回保险与清算总账差额。
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param liquidation 源账户当前强平记录，提供用户、币对、持仓腿和计划平仓量
     * @param instrument 目标合约配置，最终强平时用于验证产品和计算金额，只推进撤单时可为空
     * @param settleAssetId 强平结算资产的整数编号，余额、手续费和保险差额使用同一资产
     * @param positionKey 源强平目标持仓腿的运行时整数键，0 表示尚未定位到有效持仓
     */
    private static RuntimeTreasuryDelta executeAccountLiquidation(ExecuteLiquidationCommand command,
            TradingRuntimeState runtime, LiquidationRuntime liquidation, CoreInstrument instrument,
            int settleAssetId, long positionKey) {
        // 源账户最新持仓，实际强平数量、剩余入场信息和保证金都从它计算。
        PositionRuntime position = runtime.position(positionKey);
        // 源用户当前结算资产余额，必须存在才能计算实际扣款及释放保证金。
        BalanceRuntime balance = runtime.balance(liquidation.userId(), settleAssetId);
        if (position == null || balance == null) {
            throw new IllegalStateException("runtime liquidation entities are missing");
        }

        // 源持仓原绝对数量步数，用作本次平仓比例分摊的分母。
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        // 强平计划指定的本次平仓绝对数量步数。
        long closeQuantity = liquidation.closeQuantitySteps();
        // 强平之后剩余的绝对持仓步数，归零时清空入场价和入场价格量。
        long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
        // 本次平仓对应可释放的持仓保证金，按平仓占原数量比例分摊。
        long releasedMargin = position.positionMarginUnits() == 0 ? 0
                : proportional(position.positionMarginUnits(), closeQuantity, currentAbs);
        // 带原持仓多空方向的平仓数量，用于计算盈亏或期权现金市值。
        long signedCloseQuantity = position.signedQuantitySteps() > 0
                ? closeQuantity : Math.negateExact(closeQuantity);
        // 本次平仓的现金估值，期货为已平部分盈亏，期权为该部分带方向市值。
        long pnl = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, signedCloseQuantity,
                command.executionPriceTicks())
                : CoreContractMath.pnlUnits(instrument, signedCloseQuantity,
                position.entryPriceTicks(), command.executionPriceTicks());
        // 按执行价、平仓量及费率计算的应收强平费，后续还要受实际可扣资金限制。
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), closeQuantity, command.liquidationFeeRatePpm()));
        // 按全仓或逐仓资金边界算出的新余额、实际应用盈亏和实收费。
        LiquidationCash cash = applyCash(balance, liquidation.marginMode(), releasedMargin, pnl, feeDue);
        // 本次现金亏损绝对额中未实际从账户扣到的部分，仅比较亏损与实际扣款。
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedPnl()))) : 0;

        // 剩余持仓保留原多空方向，全部强平时为 0。
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        // 按剩余数量保留的入场价格量，空仓时归零。
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        // 平仓后的持仓新值，剩余保证金相应减少，期货累计原始平仓盈亏，期权不在此累加该字段。
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : pnl),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        // 保险基金应记的差额：实际账户盈亏的反向值加已收强平费，可能为收入或支出。
        long insuranceDelta = Math.addExact(Math.negateExact(cash.appliedPnl()), cash.collectedFee());
        // 仍有未覆盖亏空时待保险基金，否则本笔强平执行完成。
        CoreLiquidationState.Status nextStatus = uncovered > 0
                ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED;

        runtime.replaceBalance(cash.balance());
        runtime.replacePosition(positionKey, nextPosition);
        runtime.replaceLiquidation(copy(liquidation, uncovered, command.executionPriceTicks(),
                command.liquidationFeeRatePpm(), nextStatus, cash.collectedFee()));
        runtime.advanceUserRevision(liquidation.userId());
        // 本笔返回给账本的保险基金、亏空及清算对手项变化。
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        treasuryDelta.addInsurance(settleAssetId, insuranceDelta);
        treasuryDelta.addDeficit(settleAssetId, uncovered);
        treasuryDelta.addClearing(settleAssetId, uncovered);
        return treasuryDelta;
    }

    /**
     * 核对命令前业务状态版本后，同步应用本批强平撤单回执并保存下一撤单编号，暂不执行最终平仓。
     * @param before 本命令执行前的业务状态，用来检查产品线和运行时修订号一致
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param nextCursorOrderId 本批后仍待撤的下一订单编号，此继续撤单入口要求为正数
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static TradingRuntimeState applyCancellationAdvance(TradingCoreState before,
                                                               ExecuteLiquidationCommand command,
                                                               Collection<CancellationOrder> canceledOrders,
                                                               long nextCursorOrderId,
                                                               TradingRuntimeState runtime,
                                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        return applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    /**
     * 要求下一撤单编号为正，把本批已撤订单交账户步骤解冻并转为 ORDERED 继续状态，返回原运行时。
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     * @param canceledOrders Matcher 已完成的撤单回执集合，账户步骤据此解除源用户订单和资金冻结
     * @param nextCursorOrderId 本批后仍待撤的下一订单编号，此继续撤单入口要求为正数
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param identities 业务编号与运行时整数键的映射，用来定位配置、持仓和结算资产
     */
    public static TradingRuntimeState applyCancellationAdvanceRuntime(ExecuteLiquidationCommand command,
                                                                      Collection<CancellationOrder> canceledOrders,
                                                                      long nextCursorOrderId,
                                                                      TradingRuntimeState runtime,
                                                                      RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0) throw new IllegalArgumentException("invalid liquidation cancellation cursor");
        execute(null, command, canceledOrders, nextCursorOrderId, runtime, identities, false);
        return runtime;
    }

    /**
     * 按保证金模式计算新余额和实际收付：逐仓亏损最多消耗本次释放保证金，费用限于该仓剩余资金或收益；全仓先释放保证金，再以可用余额承担亏损及费用。只返回实际扣到的亏损和费用，调用方另算未覆盖亏损。
     * @param balance 源用户结算资产的余额；现金计算结果中则为应用强平后的新余额
     * @param marginMode 全仓还是逐仓，决定亏损和费用可以使用的资金边界
     * @param releasedMargin 本次计划平掉数量对应的持仓保证金，单位为结算资产最小单位
     * @param pnl 本次平仓的现金估值变化，期货为盈亏，期权为带方向市值
     * @param feeDue 本次按计划价格、数量和费率计算的应收强平费，单位为结算资产最小单位
     */
    private static LiquidationCash applyCash(BalanceRuntime balance, CoreMarginMode marginMode,
                                             long releasedMargin, long pnl, long feeDue) {
        // 局部计算中的可用余额，先释放本次保证金，再在允许范围内计入盈亏和扣费。
        long available = balance.availableUnits();
        // 局部计算中的冻结余额，只移除本次释放的持仓保证金，保留其他冻结。
        long locked = balance.lockedUnits();
        // 实际进入账户的平仓现金变化，亏损最多扣到允许使用的资金。
        long appliedPnl;
        // 按资金边界真正收到的费用，可能少于应收强平费。
        long collectedFee;
        if (marginMode == CoreMarginMode.ISOLATED) {
            if (pnl < 0) {
                // 逐仓亏损实际吃掉的本次释放保证金，不使用账户其他可用资金补该仓亏损。
                long consumedMargin = Math.min(releasedMargin, Math.negateExact(pnl));
                // 逐仓承担亏损后剩余的本次保证金，用于退回可用并限制强平费。
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
                // 全仓亏损在释放保证金后的可用余额内实际能扣掉的金额。
                long debit = Math.min(available, Math.negateExact(pnl));
                available = Math.subtractExact(available, debit);
                appliedPnl = Math.negateExact(debit);
            }
            collectedFee = Math.min(available, feeDue);
            available = Math.subtractExact(available, collectedFee);
        }
        return new LiquidationCash(new BalanceRuntime(balance.userId(), balance.assetId(), available, locked),
                appliedPnl, collectedFee);
    }

    /**
     * 只有产品可强平、持仓仍存在且配置引用、保证金模式和带方向数量与计划一致，风险记录也仍在同价格序号下判为强平时，才允许最终执行。
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param liquidation 源账户当前强平记录，提供用户、币对、持仓腿和计划平仓量
     * @param instrument 目标合约配置，最终强平时用于验证产品和计算金额，只推进撤单时可为空
     * @param positionKey 源强平目标持仓腿的运行时整数键，0 表示尚未定位到有效持仓
     */
    private static boolean executable(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      CoreInstrument instrument, long positionKey) {
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        if (positionKey == 0) return false;
        // 当前源持仓，用来核对配置引用、模式和带方向数量是否仍与计划相同。
        PositionRuntime position = runtime.position(positionKey);
        // 当前持仓腿的风险记录，必须对应计划触发价格且仍为强平等级。
        RiskSnapshotRuntime risk = runtime.riskSnapshot(positionKey);
        return position != null && position.instrument() == liquidation.instrument()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    /**
     * 核对当前行情序号与强平计划一致，命令指定的触发序号和执行价也必须匹配；不一致返回标记价过期的业务拒绝。
     * @param runtime 本命令操作的交易运行时，账户变更只在所属 Lane，资金差额交账本应用
     * @param liquidation 源账户当前强平记录，提供用户、币对、持仓腿和计划平仓量
     * @param command 本次强平执行命令，包含强平编号、触发序号、执行价、费率及当前撤单游标
     */
    private static void validatePrice(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      ExecuteLiquidationCommand command) {
        // 强平目标币对当前行情，触发序号和执行价都必须与它一致。
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence()
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    /**
     * 保留源强平身份和计划数量，复制出带新亏空、执行价、费率、实收费及阶段的记录，并把撤单继续游标清零。
     * @param current 需要保留身份及计划数量的源强平记录
     * @param deficit 执行后未覆盖的亏空金额，不能为负
     * @param priceTicks 本次记录的最终强平执行价格 tick，尚未执行或取消时可为 0
     * @param feeRatePpm 本次记录的强平费率，单位为百万分之一
     * @param status 本次处理后要保存的强平阶段
     * @param feeUnits 本次实际收取而非仅声明应收的强平费用，单位为结算资产最小单位
     */
    private static LiquidationRuntime copy(LiquidationRuntime current, long deficit, long priceTicks,
                                           long feeRatePpm, CoreLiquidationState.Status status, long feeUnits) {
        return new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                deficit, priceTicks, feeRatePpm, feeUnits, status, 0);
    }

    /**
     * 按平仓数量占原持仓数量的比例分摊整数值并截断小数；全部平掉时直接返回原值，避免无必要的中间乘法溢出。
     * @param units 需要分摊的整数保证金或入场价格量
     * @param part 本次平仓或保留的绝对数量，由调用方保证在总数量内
     * @param total 原持仓绝对数量，作为正的分摊除数
     */
    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    /**
     * 强平现金计算的局部结果，分开保存新余额、实际计入的盈亏和实际收取费用，供后续计算未覆盖亏空及账本差额。
     * @param balance 源用户结算资产的余额；现金计算结果中则为应用强平后的新余额
     * @param appliedPnl 本次实际计入账户的平仓现金变化，负亏损可能按可扣资金封顶
     * @param collectedFee 本次在允许资金范围内实际收取的强平费用
     */
    private record LiquidationCash(BalanceRuntime balance, long appliedPnl, long collectedFee) {
    }
}
