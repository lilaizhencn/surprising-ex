package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.assertj.core.api.Assertions.*;

/**
 * 在线性永续的流水执行模式下，构造两个 Matcher 分片上的待强平账户并阻塞其中一个，验证跨分片撤单先返回等待、Owner 无需直接读取账户即可推进，再检查撤单终态；可选从执行前快照重放并比较业务哈希和用户状态。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CrossShardCancellationTest {
    /** 整个测试使用的固定 Cluster 毫秒业务时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 当前测试实例生成请求的单调源序号，同时作为 UUID 低半部。 */
    private long sequence;

    /**
     * 执行带快照重放分支的跨分片强平撤单场景，检查两笔挂单都撤销，恢复后同一批次得到相同业务哈希和用户状态；没有在此另写独立资金守恒公式。
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void liquidationCancellationPollsBlockedShardAndRecoversTheSameFunds() throws Exception {
        verifyLiquidationCancellation(true);
    }

    /**
     * 跳过快照与恢复分支，仍用账户表 get 守卫覆盖强平等待及推进，验证两个分片挂单都撤销。
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void liquidationCancellationPollsBlockedShardWithoutSnapshotOrOwnerAccountReads() throws Exception {
        verifyLiquidationCancellation(false);
    }

    /**
     * 临时配置两个 Matcher，建立两个风险账户并得到两项强平工作；阻塞一分片后提交强平批次，要求返回 MATCHING_PENDING 且跨分片撤单已开始，再放行并收齐终态。可选恢复执行前快照后重放比较结果，finally 放行门闩并恢复配置。
     * @param recovery 是否在执行前保存快照并在执行后恢复重放同一强平批次
     */
    private void verifyLiquidationCancellation(boolean recovery) throws Exception {
        // 本轮临时调整的 Matcher 分片数系统属性名。
        String property = "surprising.aeron.matching-engines";
        // 原系统属性值，退出时恢复或清除。
        String previous = System.getProperty(property);
        System.setProperty(property, "2");
        // 放行被阻塞 Matcher 的门闩，finally 兜底释放。
        var release = new CountDownLatch(1);
        try (/* 本次线性永续测试运行时，退出 try 时关闭。 */ var state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 第一组账户和挂单的品种字符串。
            String first = "37";
            // 第二组品种字符串，要求路由不同分片；追加 X 仅是原有搜索分支，数字品种解析不接受此前缀。
            String second = "38";
            while (state.matchingAdapter.matcherShardId(first) == state.matchingAdapter.matcherShardId(second))
                second = "X" + second;
            register(state, first);
            register(state, second);
            seed(state, first, 1, 1000);
            seed(state, second, 3, 2000);
            applied(state, mark(first, 1, 2));
            applied(state, mark(second, 1, 2));
            // 当前风险扫描产出的强平工作页，扫描未完成时继续更新。
            CoreLiquidationWorkView work = work(state);
            for (/* 风险扫描继续命令的有限重试次数，最多十六轮。 */ int i = 0; work.riskScanPending() && i < 16; i++) {
                applied(state, command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(16))));
                work = work(state);
            }
            assertThat(work.riskScanPending()).isFalse();
            assertThat(work.actions()).hasSize(2);
            // 从扫描结果生成的强平执行批次，恢复分支重放同一条消息。
            var batch = command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work, 0, 0)));
            // 可选保存的执行前运行时快照字节，不恢复分支为空。
            byte[] snapshot = recovery ? state.snapshot(500) : null;
            // 确认阻塞控制动作已经进入目标 Matcher 的门闩。
            var entered = new CountDownLatch(1);
            // 第一品种所在的 Matcher 分片，测试通过控制调用阻塞它。
            int shard = state.matchingAdapter.matcherShardId(first);
            // 阻塞控制调用的负 token，放行后须先收集该队头结果。
            long token = state.matcherPipeline().submitControl(shard, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked matcher timed out"); }
                catch (/* 测试阻塞控制调用等待中断，转换为 AssertionError。 */ InterruptedException failure) { throw new AssertionError(failure); }
                return Boolean.TRUE;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (/* 作用域内拦截 Owner 对五类账户表 get 的测试守卫，并检查 Owner 变更集合的写入线程，退出时恢复原容器。 */ var guard = new OwnerAccountReadGuard(state.runtimeState)) {
                // 强平批次的第一次 apply 返回，要求仍是 MATCHING_PENDING。
                var pending = state.apply(batch);
                assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                assertThat(release.getCount()).isOne();
                // 轮询跨分片撤单开始标记的两秒截止点。
                long checkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!state.pendingMatching().get(state.matchingSequence(batch.header().commandId())).crossShardCancellationStarted()) {
                    if (System.nanoTime() >= checkDeadline) throw new AssertionError("liquidation Lane check timeout");
                    state.matchingProgress.progressPlaceBatchAdmissions();
                    Thread.onSpinWait();
                }
                assertThat(state.pendingMatching().get(state.matchingSequence(batch.header().commandId())).crossShardCancellationStarted()).isTrue();
                release.countDown();
                // 收集阻塞控制动作完成结果的两秒截止点。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                // 控制调用轮询返回的可空结果，最终必须是 Boolean.TRUE。
                Object complete;
                do { complete = state.matcherPipeline().pollControl(shard, token); Thread.onSpinWait(); }
                while (complete == null && System.nanoTime() < deadline);
                assertThat(complete).isEqualTo(Boolean.TRUE);
                // 放行后由测试完成器收齐的强平批次终态，要求 APPLIED。
                var terminal = CoreTestCompletion.completeMatchingSynchronously(state,
                        state.matchingSequence(batch.header().commandId()), TIME, batch.header().sourceSequence());
                assertThat(terminal.status()).isEqualTo(ResponseStatus.APPLIED);
            }
            assertThat(state.tradingState().order(1003).status()).isEqualTo(com.surprising.aeron.model.CoreOrderStatus.CANCELED);
            assertThat(state.tradingState().order(2003).status()).isEqualTo(com.surprising.aeron.model.CoreOrderStatus.CANCELED);
            if (recovery) try (/* 从批次执行前快照恢复的独立运行时，重放后比较哈希与用户状态。 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                applied(restored, batch);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(restored.tradingState().users()).isEqualTo(state.tradingState().users());
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    /**
     * 注册单位价格/数量步长的线性永续品种，设置测试需要的初始及维持保证金参数，要求命令已应用。
     * @param state 本次真实线性永续测试运行时
     * @param instrumentId 属于指定 Matcher 分片的数字字符串品种号
     */
    private void register(TradingCoreRuntime state, String instrumentId) {
        applied(state, command(CoreMessageType.REGISTER_INSTRUMENT, 0,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(instrumentId,
                        ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0))));
    }

    /**
     * 先以标记价一百建立用户净多仓，再为同一用户挂低价买单；随后降低标记价时可产生强平并要求先撤该挂单。通过真实命令调整 USDT 余额和成交。
     * @param state 本次真实线性永续测试运行时
     * @param instrumentId 属于指定 Matcher 分片的数字字符串品种号
     * @param user 场景中的风险用户编号，另一方使用加一的编号
     * @param order 场景的订单编号基数，成交单与挂单分别使用后续编号
     */
    private void seed(TradingCoreRuntime state, String instrumentId, long user, long order) {
        applied(state, mark(instrumentId, 100, 1));
        applied(state, command(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 110))));
        applied(state, command(CoreMessageType.ADJUST_BALANCE, user + 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        applied(state, place(user + 1, instrumentId, order + 1, CoreOrderSide.SELL, 100, 10));
        applied(state, place(user, instrumentId, order + 2, CoreOrderSide.BUY, 100, 10));
        applied(state, place(user, instrumentId, order + 3, CoreOrderSide.BUY, 1, 1));
    }

    /**
     * 编码全仓净持仓的 GTC 限价单，客户端订单键随订单号固定，不在辅助器里直接改订单簿。
     * @param user 场景中的风险用户编号，另一方使用加一的编号
     * @param instrumentId 属于指定 Matcher 分片的数字字符串品种号
     * @param order 场景的订单编号基数，成交单与挂单分别使用后续编号
     * @param side 订单买卖方向
     * @param price 限价或标记价格 tick 数
     * @param quantity 下单数量步数
     */
    private CoreMessage place(long user, String instrumentId, long order, CoreOrderSide side, long price, long quantity) {
        return command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(order, instrumentId, side, price, quantity, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + order)));
    }
    /**
     * 编码带固定 Cluster 时间及指定修订号的标记价更新命令。
     * @param instrumentId 属于指定 Matcher 分片的数字字符串品种号
     * @param price 限价或标记价格 tick 数
     * @param revision 标记价更新的单调修订号
     */
    private CoreMessage mark(String instrumentId, long price, long revision) {
        return command(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(instrumentId, price, revision, TIME)));
    }
    /**
     * 同步查询线性永续执行用途的强平工作，最多一百项并限制响应字节，解码协议结果。
     * @param state 本次真实线性永续测试运行时
     */
    private CoreLiquidationWorkView work(TradingCoreRuntime state) {
        // 查询强平执行工作的一次完整协议消息。
        var query = command(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,
                        CoreLiquidationWorkView.Purpose.EXECUTION, 0, 100, 1_048_576));
        return CoreLiquidationWorkCodec.decodeWork(CoreTestCompletion.querySynchronously(state, query).data());
    }
    /**
     * 递增测试源序号并生成固定高半部的 UUID，强平工作请求构造 query 头，其余构造 command 头；全部绑定线性永续与固定业务时间。
     * @param type 要编码的查询或命令消息类型
     * @param user 场景中的风险用户编号，另一方使用加一的编号
     * @param payload 对应协议已经编码的消息体
     */
    private CoreMessage command(CoreMessageType type, long user, byte[] payload) {
        // 本次递增后的源序号，也作为 UUID 低半部与相关性标识。
        long next = ++sequence;
        // 固定高半部八百八十一、本次源序号为低半部的命令 UUID。
        var id = new UUID(881, next);
        // 按查询或命令类型构造的线性永续 Core 消息头。
        var header = type == CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type, id, ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                881, next, user, TIME, next)
                : CoreMessageHeader.command(type, id, ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS,
                881, next, user, TIME, next);
        return new CoreMessage(header, payload);
    }
    /**
     * 通过测试完成器把命令推进到终态，并断言状态 APPLIED；不是只检查 apply 的初次返回值。
     * @param state 本次真实线性永续测试运行时
     * @param command 需要推进到 APPLIED 的完整 Core 命令
     */
    private static void applied(TradingCoreRuntime state, CoreMessage command) {
        // 经过测试完成器推进后返回的最终命令响应。
        var response = CoreTestCompletion.applyAsynchronously(state, command, TIME, command.header().sourceSequence());
        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
    }
}
