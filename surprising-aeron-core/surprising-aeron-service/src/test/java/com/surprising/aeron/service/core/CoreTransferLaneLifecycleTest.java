package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePendingTransferCodec;
import com.surprising.aeron.protocol.CorePendingTransferView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * PIPELINED 下的真实划转入口与分页读取测试，不使用快照恢复；在既有 Owner 账户读取守卫内检查账户归属、业务身份冲突、完成重试及无所有权交接。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreTransferLaneLifecycleTest {
    /** 本测试固定的复制业务时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试命令与查询共用递增身份，不是运行时业务水位。 */
    private long sequence;

    /**
     * 选择分属两条 Lane 的源用户，分别转出30和10到另一产品用户22。检查同 UUID 和同业务身份重试、分页轮转、金额及用户冲突、余额不足无残留路由、错误完成用户拒绝、重复完成不增修订，最后核对70、190与40。
     * @param product 本轮作为源端的枚举产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void laneOwnsIdentityDebitCompletionAndPagedQueriesAcrossProducts(ProductLine product) throws Exception {
        // 与源不同的目标产品线
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (/* 持有源用户和待完成记录的实际运行时 */ var source = new TradingCoreRuntime(product); /* 持有收款用户22的目标运行时 */ var target = new TradingCoreRuntime(destination)) {
            // 第一个源用户，固定为7
            // 递增选择的第二用户，直到路由到另一账户 Lane
            long first = 7, second = 8;
            while (source.runtimeState.topology().accountLaneId(second) == source.runtimeState.topology().accountLaneId(first)) second++;
            deposit(source, first, 100); deposit(source, second, 200);
            // 进入守卫前的所有权交接轮次，结束时要求不变
            long handoff = source.runtimeState.laneHandoffEpoch();
            // 业务ID91、金额30的第一用户划转
            var a = transfer(product, destination, 91, first, 30);
            // 业务ID92、金额10的第二用户划转
            var b = transfer(product, destination, 92, second, 10);
            try (/* 既有 Owner 账户读取守卫，限定实际命令和查询执行边界 */ var guard = new OwnerAccountReadGuard(source.runtimeState)) {
                assertThatThrownBy(() -> source.runtimeState.pendingTransfer(91))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Account Lane");
                // 第一条转出请求，同对象重试检查请求去重
                var out = command(source, CoreMessageType.TRANSFER_OUT, first, TradingCommandCodec.encodeTransferFunds(a));
                assertThat(apply(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(apply(source, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(a))).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, second,
                        TradingCommandCodec.encodeTransferFunds(b))).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(query(source, 256)).extracting(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> v.command().transferId()).containsExactly(91L, 92L);
                assertThat(query(source, 1)).extracting(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> v.command().transferId()).containsExactly(91L);
                assertThat(query(source, 1)).extracting(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> v.command().transferId()).containsExactly(92L);
                assertThat(query(source, 1)).extracting(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> v.command().transferId()).containsExactly(91L);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 91, first, 31)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, second,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 91, second, 30)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 93, first, 71)))).resultCode())
                        .isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(source.runtimeState.pendingTransferUser(93)).isZero();
                for (var /* 本轮入账或完成的原划转参数 */ transfer : List.of(a, b)) {
                    // 目标产品用户22的转入请求
                    var credit = command(target, CoreMessageType.TRANSFER_IN, 22, TradingCommandCodec.encodeTransferFunds(transfer));
                    assertThat(apply(target, credit).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(apply(target, credit).status()).isEqualTo(ResponseStatus.DUPLICATE);
                }
                assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, second,
                        TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(91)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                for (var /* 本轮入账或完成的原划转参数 */ transfer : List.of(a, b)) {
                    assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, transfer.sourceUserId(),
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(transfer.transferId()))))
                            .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    // 首次完成后保存的修订，重复完成后要求保持不变
                    long revision = source.runtimeState.revision();
                    assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, transfer.sourceUserId(),
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(transfer.transferId()))))
                            .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(source.runtimeState.revision()).isEqualTo(revision);
                }
                assertThat(query(source, 256)).isEmpty();
                assertThat(source.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            // 完成后的第一源用户只读视图，余额70
            var firstUser = CoreTestCompletion.userState(source, first);
            // 完成后的第二源用户视图，余额190
            var secondUser = CoreTestCompletion.userState(source, second);
            // 目标用户22视图，累计收款40
            var recipient = CoreTestCompletion.userState(target, 22);
            assertThat(firstUser.balances()).singleElement().satisfies(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> {
                assertThat(v.availableUnits()).isEqualTo(70); assertThat(v.lockedUnits()).isZero();
            });
            assertThat(secondUser.balances()).singleElement().satisfies(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> assertThat(v.availableUnits()).isEqualTo(190));
            assertThat(recipient.balances()).singleElement().satisfies(/* 当前分页结果或余额视图，按本断言读取业务身份及数值 */ v -> assertThat(v.availableUnits()).isEqualTo(40));
            assertThat(firstUser.reservations()).isEmpty(); assertThat(secondUser.positions()).isEmpty();
            source.assertClusterCallbackComplete(); target.assertClusterCallbackComplete();
        }
    }

    /**
     * 生成从指定源用户到目标产品用户22的USDT划转，账户类型随产品线固定。
     * @param source 源产品线
     * @param target 目标产品线
     * @param id 划转业务 ID
     * @param user 命令所属用户或划转源用户
     * @param amount 资金整数数量
     */
    private TransferFundsCommand transfer(ProductLine source, ProductLine target, long id, long user, long amount) {
        return new TransferFundsCommand(id, source, target, source.accountTypeCode(), target.accountTypeCode(),
                "USDT", amount, "lane-transfer-" + id, "", user, 22);
    }

    /**
     * 通过真实余额命令为指定测试用户增加资金，并要求处理成功。
     * @param core 本次操作的实际交易运行时
     * @param user 命令所属用户或划转源用户
     * @param amount 资金整数数量
     */
    private void deposit(TradingCoreRuntime core, long user, long amount) {
        assertThat(apply(core, command(core, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount))))
                .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 使用既有异步完成帮助入口取得本命令终态。
     * @param core 本次操作的实际交易运行时
     * @param message 交给异步完成帮助器的原请求
     */
    private CoreResponse apply(TradingCoreRuntime core, CoreMessage message) {
        return CoreTestCompletion.applyAsynchronously(core, message);
    }

    /**
     * 递增本测试序号并生成固定来源的命令，目标产品取自所给运行时。
     * @param core 本次操作的实际交易运行时
     * @param type 本次命令协议类型
     * @param user 命令所属用户或划转源用户
     * @param payload 完整原命令载荷
     */
    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 本测试刚分配的命令或查询身份
        long id = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(941, id), core.productLine,
                CommandSource.OPERATIONS, 941, id, user, TIME, id), payload);
    }

    /**
     * 直接发有限页划转查询，要求准备阶段不取得 Owner 账户访问权；返回等待码时按查询身份轮询最多五秒，再解码成功页。
     * @param core 本次操作的实际交易运行时
     * @param limit 有限页最大条数
     */
    private List<CorePendingTransferView> query(TradingCoreRuntime core, int limit) {
        // 本测试刚分配的命令或查询身份
        long id = ++sequence;
        // 本次有限页查询原请求
        var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.PENDING_TRANSFER_QUERY,
                new UUID(942, id), core.productLine, CommandSource.OPERATIONS, 942, id, 0, TIME, id),
                CorePendingTransferCodec.encodeQuery(limit));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        // 查询首响应或最终异步结果
        var response = core.apply(request);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            // 原查询 UUID 对应的待完成查询身份
            long queryId = core.bookQueries.querySequence(request.header().commandId());
            // 异步查询等待五秒的单调纳秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((response = core.bookQueries.takeQueryResult(queryId)) == null) {
                core.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("pending transfer query timed out");
                Thread.onSpinWait();
            }
        }
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CorePendingTransferCodec.decode(response.data());
    }
}
