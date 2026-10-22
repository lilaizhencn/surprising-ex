package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.account.TransferRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 在 PIPELINED 下检查全局冷业务哈希：同一读取围栏内收齐各 Lane 的原费用政策和待完成划转，读后归还所有权。覆盖六产品线，并确认调用前已经持有的 Owner 访问权不会被查询错误释放。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreBusinessStateHashTest {
    /** 本测试全部业务命令使用的固定毫秒时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试来源的下一命令序号累计值。 */
    private long sequence;

    /**
     * 让两个不同 Lane 用户入账并各发起划转，独立组合冷状态、政策及按 ID 排序的划转得预期哈希；检查权限归还、重复不变、原持权围栏不换代，再完成一笔划转和追加资金验证后续写可继续。
     * @param product 本轮隔离检查的产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void hashesPoliciesAndTransfersInOneFenceAndResumesWorkers(ProductLine product) {
        try (/* 本轮 PIPELINED 交易运行时，结束自动关闭 */ var core = new TradingCoreRuntime(product)) {
            // 从八开始选择并确保属于另一 Lane 的第二用户
            // 首个用户七
            long first = 7, second = 8;
            while (core.runtimeState.topology().accountLaneId(first)
                    == core.runtimeState.topology().accountLaneId(second)) second++;
            apply(core, CoreMessageType.ADJUST_BALANCE, first,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            apply(core, CoreMessageType.ADJUST_BALANCE, second,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 200)));
            // 归属首用户、maker 返佣十及 taker 费率二十的政策参数
            var policy = new UpsertFeePolicyCommand(91, 1, first, "", -10, 20, 1, true, TIME - 1, 0);
            apply(core, CoreMessageType.UPSERT_FEE_POLICY, first, TradingCommandCodec.encodeUpsertFeePolicy(policy));
            // 首用户金额三十的待完成划转一百零一
            var a = transfer(product, 101, first, 30);
            // 第二用户金额十的待完成划转一百零二
            var b = transfer(product, 102, second, 10);
            // 首笔转出请求，后面用完全相同消息核对幂等
            CoreMessage command = command(core, CoreMessageType.TRANSFER_OUT, first,
                    TradingCommandCodec.encodeTransferFunds(a));
            assertThat(CoreTestCompletion.applyAsynchronously(core, command).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            apply(core, CoreMessageType.TRANSFER_OUT, second, TradingCommandCodec.encodeTransferFunds(b));
            // 独立构造的政策冷值对照映射
            var policies = Map.of(91L, CoreFeePolicyState.from(policy));
            // 两份独立构造的原待完成划转对照映射
            var transfers = Map.of(101L, new TransferRuntime(first, a), 102L, new TransferRuntime(second, b));
            // 哈希规范要求按划转 ID 排序，不能依赖输入映射自身的遍历顺序。
            // 按划转业务 ID 升序的对照映射，避免原 map 遍历顺序影响预期
            var orderedTransfers = new java.util.TreeMap<>(transfers);
            // 不含运行时额外政策及划转组合的冷状态业务哈希
            long base = core.tradingState().businessStateHash();
            // 将冷哈希、原政策及有序待完成划转按规范顺序组合的预期值
            long expected = TradingCoreRuntime.canonicalBusinessStateHash(base, policies, orderedTransfers);
            assertThat(expected).isNotEqualTo(TradingCoreRuntime.canonicalBusinessStateHash(base, policies, Map.of()));
            assertThat(expected).isNotEqualTo(TradingCoreRuntime.canonicalBusinessStateHash(base, Map.of(), orderedTransfers));
            assertThat(core.runtimeState.ownerLaneAccess()).isFalse();
            assertThatThrownBy(() -> core.runtimeState.pendingTransfersSnapshot()).isInstanceOf(IllegalStateException.class);
            assertThat(core.stateHash()).isEqualTo(expected);
            assertThat(core.runtimeState.ownerLaneAccess()).isFalse();
            assertThatThrownBy(() -> core.runtimeState.pendingTransfersSnapshot()).isInstanceOf(IllegalStateException.class);
            assertThat(CoreTestCompletion.applyAsynchronously(core, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(core.stateHash()).isEqualTo(expected);

            while (!core.runtimeState.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            // 调用前已经持有 Owner 访问权时的原交接代号，查询不得另作交接
            long epoch = core.runtimeState.laneHandoffEpoch();
            try {
                assertThat(core.stateHash()).isEqualTo(expected);
                assertThat(core.runtimeState.ownerLaneAccess()).isTrue();
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(epoch);
            } finally { core.runtimeState.releaseOwnerLaneAccess(); }

            apply(core, CoreMessageType.COMPLETE_TRANSFER, first,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(101)));
            // 完成首笔划转后冷状态的基础业务哈希
            long completedBase = core.tradingState().businessStateHash();
            // 完成首笔后只组合剩余第二笔划转得到的预期哈希
            long completedHash = TradingCoreRuntime.canonicalBusinessStateHash(completedBase, policies,
                    Map.of(102L, new TransferRuntime(second, b)));
            assertThat(core.stateHash()).isEqualTo(completedHash).isNotEqualTo(expected);
            // 读取后继续完成业务写入，检查查询已经把账户所有权还给工作线程。
            apply(core, CoreMessageType.ADJUST_BALANCE, first,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(core.stateHash()).isNotEqualTo(completedHash);
            assertThat(core.runtimeState.ownerLaneAccess()).isFalse();
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 给指定用户构造到另一产品线的待完成 USDT 划转；现货转 U 本位永续，其余转现货，账户类型与各产品线匹配。
     * @param source 划转发起产品线
     * @param id 待完成划转的业务 ID
     * @param user 请求或划转所属用户
     * @param amount 划转的 USDT 单位数
     */
    private TransferFundsCommand transfer(ProductLine source, long id, long user, long amount) {
        // 按源产品线选出的另一目标产品线
        ProductLine target = source == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        return new TransferFundsCommand(id, source, target, source.accountTypeCode(), target.accountTypeCode(),
                "USDT", amount, "hash-" + id, "核对", user, 22);
    }

    /**
     * 构造并执行一条原业务命令，要求最终 commandStatus 为 APPLIED。
     * @param core 当前产品线交易运行时
     * @param type 当前原业务消息类型
     * @param user 请求或划转所属用户
     * @param payload 已经按该消息类型编码的原载荷
     */
    private void apply(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        assertThat(CoreTestCompletion.applyAsynchronously(core, command(core, type, user, payload)).commandStatus())
                .isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 递增本测试来源序号，以固定业务时间和可复现 UUID 构造所属产品线消息。
     * @param core 当前产品线交易运行时
     * @param type 当前原业务消息类型
     * @param user 请求或划转所属用户
     * @param payload 已经按该消息类型编码的原载荷
     */
    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 为本次原命令递增的来源序号，同时组成 UUID 及关联号
        long id = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(954, id), core.productLine,
                CommandSource.OPERATIONS, 954, id, user, TIME, id), payload);
    }
}
