package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实账户 Lane 前置检查与 Matcher 撤单顺序测试：覆盖撤单预算继续、过期价格、失效风险，以及失败动作不能提前删除有效订单；不承担快照、恢复或负载测试。
 */
class CoreLiquidationLaneCheckTest {
    /** 本组测试统一使用的 Unix 毫秒时间戳，避免本机时间影响价格和命令顺序。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 当前测试实例已经分配的消息序号，下次发送前递增一次。 */
    private long sequence;

    /**
     * 为两账户强平每批只给一笔撤单预算，在 Owner 读表哨兵下分四批完成，检查 Lane 所有权不转移、资金守恒、四笔挂单撤销和两用户持仓归零。
     */
    @Test
    void cancellationBudgetResumesAcrossLanesWithoutOwnerAccountReads() throws Exception {
        try (/* 已经准备资金、持仓、挂单和强平计划的运行时，测试结束自动关闭。 */ var core = prepared()) {
            // 开始分批强平前的资金守恒总额，全部批次结束后必须相同。
            long funds = funds(core);
            // 已经执行的强平批次数量，用来防止游标不推进或重复处理。
            int pages = 0;
            for (/* 当前仍未完成的真实强平工作，处理后再次查询取得下一批动作和游标。 */ var work = work(core); !work.actions().isEmpty(); work = work(core)) {
                assertThat(++pages).isLessThanOrEqualTo(4);
                // 每批撤单预算为一笔的强平执行请求，强制两用户跨多批推进。
                var batch = new ExecuteLiquidationBatchCommand(
                        ExecuteLiquidationBatchCommand.fromWork(work, 0, 0).actions(), 1, 0, null, 0);
                // 该笔或该批强平的最终响应，稍后解码处理数量和失效动作数。
                CoreResponse result;
                // 执行前的 Lane 所有权交接代次，Owner 不应为读取账户而改变它。
                long handoff = core.runtimeState.laneHandoffEpoch();
                try (/* 禁止独立 Lane 模式下 Owner 直接读取账户表的测试哨兵。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    result = applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(batch));
                }
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                // 批执行返回的动作及撤单统计，用来核对预算、待处理项或过期动作数。
                var summary = CoreLiquidationBatchResultCodec.decode(result.data());
                assertThat(summary.processedOrders()).isEqualTo(1);
                if (pages == 1) assertThat(summary.pendingActions()).isEqualTo(2);
            }
            assertThat(pages).isEqualTo(4);
            assertThat(funds(core)).isEqualTo(funds);
            assertThat(core.tradingState().orders().values()).hasSize(4)
                    .allSatisfy(/* 最终检查的历史挂单记录，要求其状态已经撤销。 */ order -> assertThat(order.status()).isEqualTo(CoreOrderStatus.CANCELED));
            assertThat(core.tradingState().users().get(1L).positions().values())
                    .allSatisfy(/* 最终逐条检查的用户持仓，全部强平后带方向数量应为 0。 */ position -> assertThat(position.signedQuantitySteps()).isZero());
            assertThat(core.tradingState().users().get(7L).positions().values())
                    .allSatisfy(/* 最终逐条检查的用户持仓，全部强平后带方向数量应为 0。 */ position -> assertThat(position.signedQuantitySteps()).isZero());
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 先生成批强平输入再刷新价格，旧动作应全部过期且不改账户或账本；随后真实撤单仍成功，证明 Matcher 的有效订单没有被旧强平提前删掉。
     */
    @Test
    void staleBatchPreservesAccountOrdersAndMatcherOrders() throws Exception {
        try (/* 已经准备资金、持仓、挂单和强平计划的运行时，测试结束自动关闭。 */ var core = prepared()) {
            // 价格更新前捕获的旧批强平输入，刷新价格后应全部判为过期。
            var stale = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0);
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 2, 3, TIME)));
            // 执行过期或失效强平前的业务状态，后续比较账户、订单与账本不变。
            var before = core.tradingState();
            // 该笔或该批强平的最终响应，稍后解码处理数量和失效动作数。
            CoreResponse result;
            try (/* 禁止独立 Lane 模式下 Owner 直接读取账户表的测试哨兵。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                result = applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(stale));
            }
            // 批执行返回的动作及撤单统计，用来核对预算、待处理项或过期动作数。
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

    /**
     * 在所属 Lane 把用户风险改为正常后执行旧强平动作，只取消失效计划，用户余额、持仓、订单及账本保持原值，原挂单仍能真实撤销。
     */
    @Test
    void obsoleteLaneRiskCancelsOnlyPlanAndLeavesRestingOrdersValid() throws Exception {
        try (/* 已经准备资金、持仓、挂单和强平计划的运行时，测试结束自动关闭。 */ var core = prepared()) {
            // 从真实强平工作中选出的目标动作，用来构造指定用户或单笔旧价格执行。
            var action = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0).actions().stream().filter(/* 从工作列表中筛选的动作，按用户编号找到用户 1 的计划。 */ value -> value.userId() == 1).findFirst().orElseThrow();
            // 合约 1 当前运行时配置，用于定位用户 1 的持仓风险键。
            var instrument = core.runtimeState.instrument("1");
            // 用户 1 净持仓的整数键，所属 Lane 会在这个位置把风险状态改为正常。
            long key = core.identities.findPositionKeyValue(1, instrument, CorePositionSide.NET);
            core.runtimeState.dispatchControlLanes(core.runtimeState.topology().accountLaneMask(1), /* 当前被派发的账户 Lane，回调内只按已准备的持仓键修改该用户风险。 */ ignored -> {
                // 所属 Lane 读取的原风险记录，复制全部事实，仅把结论改为 NORMAL。
                var risk = core.runtimeState.riskSnapshot(key);
                core.runtimeState.putRiskSnapshot(key, new RiskSnapshotRuntime(risk.userId(), risk.symbolId(),
                        risk.positionSide(), risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm(), CoreRiskStatus.NORMAL));
                return null;
            });
            // 等待测试风险修改回调完成的单调时钟截止值，最多等五秒。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!core.runtimeState.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("risk fixture Lane timeout");
                Thread.onSpinWait();
            }
            core.runtimeState.clearCommittedChanges(core.identities);
            // 执行过期或失效强平前的业务状态，后续比较账户、订单与账本不变。
            var before = core.tradingState();
            try (/* 禁止独立 Lane 模式下 Owner 直接读取账户表的测试哨兵。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                applied(core, CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                        TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                                List.of(action), 10, 0, null, 0)));
            }
            // 失效强平处理后的业务状态，计划应取消但其他资金和订单事实不变。
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

    /**
     * 单笔强平使用旧价格时应在 Matcher 撤单前返回 STALE_MARK_PRICE，账户、订单和账本保持原值，随后挂单仍可撤销。
     */
    @Test
    void staleSingleRejectsBeforeMatcherCancellation() throws Exception {
        try (/* 已经准备资金、持仓、挂单和强平计划的运行时，测试结束自动关闭。 */ var core = prepared()) {
            // 从真实强平工作中选出的目标动作，用来构造指定用户或单笔旧价格执行。
            var action = ExecuteLiquidationBatchCommand.fromWork(work(core), 0, 0).actions().getFirst();
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 2, 3, TIME)));
            // 执行过期或失效强平前的业务状态，后续比较账户、订单与账本不变。
            var before = core.tradingState();
            // 按旧动作的价格版本和执行价构造的单笔强平命令。
            var command = new ExecuteLiquidationCommand(action.liquidationId(), action.triggerPriceSequence(),
                    action.executionPriceTicks(), 0);
            try (/* 禁止独立 Lane 模式下 Owner 直接读取账户表的测试哨兵。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // 当前查询或命令得到的最终响应，调用方核对业务状态和结果数据。
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

    /**
     * 准备线性永续合约和三账户资金，让用户 1、7 各成交十步多头并挂两笔只减仓卖单，再让标记价大跌并扫出两份强平计划；准备失败时关闭运行时。
     */
    private TradingCoreRuntime prepared() {
        // 已经准备资金、持仓、挂单和强平计划的运行时，测试结束自动关闭。
        var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL);
        try {
            applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0)));
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long /* 当前准备或撤销挂单的测试用户，资金守恒汇总时表示当前累计的账户。 */ user : new long[]{1, 2, 7}) applied(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 2 ? 10_000 : 110)));
            place(core, 2, 100, CoreOrderSide.SELL, 100, 20, false);
            for (long /* 当前准备或撤销挂单的测试用户，资金守恒汇总时表示当前累计的账户。 */ user : new long[]{1, 7}) {
                place(core, user, 100 + user, CoreOrderSide.BUY, 100, 10, false);
                for (/* 每个目标用户两笔只减仓挂单中的下标，用来生成并再次定位固定订单编号。 */ int index = 0; index < 2; index++)
                    place(core, user, 200 + user * 10 + index, CoreOrderSide.SELL, 110, 1, true);
            }
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 1, 2, TIME)));
            for (/* 准备阶段继续风险扫描的命令次数，限制最多二十次以暴露扫描不推进。 */ int polls = 0; core.runtimeState.firstRiskIncompleteScan() != null; polls++) {
                assertThat(polls).isLessThan(20);
                applied(core, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
            }
            assertThat(work(core).actions()).hasSize(2);
            return core;
        } catch (/* 准备运行时期间发生的失败，先关闭本次运行时再原样抛出。 */ Throwable failure) { core.close(); throw failure; }
    }

    /**
     * 在禁止 Owner 越界读账户的哨兵内，真实撤销用户 1、7 各自两笔挂单，确认订单清空且集群回调已完成。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     */
    private void cancelRemainingOrders(TradingCoreRuntime core) throws Exception {
        try (/* 禁止独立 Lane 模式下 Owner 直接读取账户表的测试哨兵。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
            for (long /* 当前准备或撤销挂单的测试用户，资金守恒汇总时表示当前累计的账户。 */ user : new long[]{1, 7}) for (/* 每个目标用户两笔只减仓挂单中的下标，用来生成并再次定位固定订单编号。 */ int index = 0; index < 2; index++)
                applied(core, CoreMessageType.CANCEL_ORDER, user,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(200 + user * 10 + index)));
        }
        assertThat(core.tradingState().orders()).isEmpty();
        core.assertClusterCallbackComplete();
    }

    /**
     * 按统一全仓净持仓限价 GTC 属性发送一笔测试订单，并要求异步处理结果为已应用。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     * @param user 发起这笔交易命令的用户编号，系统级查询或控制命令使用 0
     * @param id 当前测试订单的唯一编号
     * @param side 测试订单的买入或卖出方向
     * @param price 委托价格 tick
     * @param quantity 委托数量步数
     * @param reduceOnly 是否只减已有持仓，准备强平挂单时设为 true
     */
    private void place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long price,
                       long quantity, boolean reduceOnly) {
        applied(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, price, quantity, reduceOnly, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "lane-check-" + id)));
    }

    /**
     * 查询并解码待执行强平工作，要求查询成功，给批次构造提供真实动作和游标。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     */
    private CoreLiquidationWorkView work(TradingCoreRuntime core) {
        // 当前查询或命令得到的最终响应，调用方核对业务状态和结果数据。
        var response = apply(core, CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(core.productLine, CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1000, 1_048_576));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    /**
     * 汇总用户 USDT 总余额、保险余额和清算对手余额，再减保险亏空，形成测试中用于前后核对的资金守恒总额。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     */
    private long funds(TradingCoreRuntime core) {
        // 当前用于资金守恒汇总的业务状态。
        var state = core.tradingState();
        // 先累加所有用户余额，再加入保险和清算余额、扣除亏空的资金检查总额。
        long total = state.users().values().stream().mapToLong(/* 当前准备或撤销挂单的测试用户，资金守恒汇总时表示当前累计的账户。 */ user -> user.totalUnits("USDT")).sum();
        total = Math.addExact(total, state.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, state.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L));
        return Math.subtractExact(total, state.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));
    }

    /**
     * 发送命令并要求最终业务状态为 APPLIED，失败断言带消息类型和结果码便于定位。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     * @param type 本次查询或命令的协议消息类型
     * @param user 发起这笔交易命令的用户编号，系统级查询或控制命令使用 0
     * @param payload 已经按该消息类型编码的业务载荷
     */
    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 当前查询或命令得到的最终响应，调用方核对业务状态和结果数据。
        var response = apply(core, type, user, payload);
        assertThat(response.commandStatus()).as("%s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }

    /**
     * 为每次调用分配唯一序号与命令编号，按强平工作查询或写命令分别生成消息头，再通过对应测试完成辅助器取得最终响应。
     * @param core 正在接受测试订单、行情、强平或查询的交易运行时
     * @param type 本次查询或命令的协议消息类型
     * @param user 发起这笔交易命令的用户编号，系统级查询或控制命令使用 0
     * @param payload 已经按该消息类型编码的业务载荷
     */
    private CoreResponse apply(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 为当前消息新分配的递增序号，同时写入命令编号和消息头。
        long next = ++sequence;
        // 由固定测试前缀和当前序号组成的唯一命令编号。
        var id = new UUID(951, next);
        // 根据查询或写命令类型构造的协议头，包含产品线、用户和固定时间。
        var header = type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type, id, core.productLine, CommandSource.OPERATIONS, 951, next, user, TIME, next)
                : CoreMessageHeader.command(type, id, core.productLine, CommandSource.OPERATIONS, 951, next, user, TIME, next);
        // 消息头与业务载荷组成的完整请求，交相应完成辅助器发送。
        var message = new CoreMessage(header, payload);
        return type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreTestCompletion.querySynchronously(core, message)
                : CoreTestCompletion.applyAsynchronously(core, message);
    }
}
