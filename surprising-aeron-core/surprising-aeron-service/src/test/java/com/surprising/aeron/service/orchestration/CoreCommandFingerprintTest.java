package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令 UUID 与业务载荷指纹的幂等测试：改变载荷必须在再次变更资金前冲突，相同载荷跨来源元数据和快照恢复仍返回原终态；不把新来源序号当作同 UUID 新业务。
 */
class CoreCommandFingerprintTest {

    /**
     * 同一 UUID 首次入账七，再改成九十九及其他请求元数据，检查幂等冲突、余额仍七、命令计数及来源序号仍停在首条。
     */
    @Test
    void changedPayloadWithSameCommandIdConflictsBeforeAnyMutation() {
        try (/* 本轮用于提交原命令的交易运行时；try 作用域版本退出自动关闭 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 首条及重试共用的命令 UUID
            UUID commandId = UUID.randomUUID();
            // 首次将用户余额增加七的现货请求
            CoreMessage first = probe(commandId, 1, 7, 1_000, 11);
            // 同一 UUID 改成不同余额增量的冲突消息
            CoreMessage changed = probe(commandId, 2, 99, 9_999, 12);

            assertThat(CoreTestCompletion.applyAsynchronously(state, first).status()).isEqualTo(ResponseStatus.APPLIED);
            // 变化载荷重试得到的幂等冲突响应
            CoreResponse conflict = CoreTestCompletion.applyAsynchronously(state, changed);

            assertThat(conflict.resultCode().name()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(testBalance(state, 1001)).isEqualTo(7);
            assertThat(state.appliedCommandCount()).isOne();
            assertThat(state.lastSourceSequences())
                    .containsEntry(new TradingCoreRuntime.SourceKey(CommandSource.GATEWAY, 7), 1L);
        }
    }

    /**
     * 现货首次入账后恢复快照，以同 UUID、同业务载荷而不同来源和请求时间重试；核对返回原终态、数据及计数，业务哈希和来源水位不变。
     */
    @Test
    void sameFingerprintReplaysOriginalAfterSourceEpochAndSnapshotRestore() {
        // 本轮用于提交原命令的交易运行时；try 作用域版本退出自动关闭
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        // 首条及重试共用的命令 UUID
        UUID commandId = UUID.randomUUID();
        // 首次成功提交的终态响应，供恢复后重复结果逐字段对照
        CoreResponse original = CoreTestCompletion.applyAsynchronously(state, probe(commandId, 1, 7, 1_000, 11));
        // 原运行时提交后编码出的完整快照字节
        byte[] snapshot = state.snapshot();
        // 从原快照恢复的独立交易运行时
        TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, snapshot);
        // 重试前恢复运行时的状态哈希，重复不能改变它
        long stateHash = restored.stateHash();
        // 重试前已经应用的命令数，重复不能再次增加
        long appliedCommandCount = restored.appliedCommandCount();
        // 重试前恢复出的来源水位映射，重复不能用新来源改写它
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = restored.lastSourceSequences();

        // 同业务载荷跨来源元数据重试得到的重复终态
        CoreResponse replay = CoreTestCompletion.applyAsynchronously(restored, new CoreMessage(
                CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId, ProductLine.SPOT,
                        CommandSource.GATEWAY, 999, 4_000, 1001, 8_000, 44),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7))));

        assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(replay.commandStatus()).isEqualTo(original.commandStatus());
        assertThat(replay.resultCode()).isEqualTo(original.resultCode());
        assertThat(replay.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(replay.data()).containsExactly(original.data());
        assertThat(restored.stateHash()).isEqualTo(stateHash);
        assertThat(restored.appliedCommandCount()).isEqualTo(appliedCommandCount);
        assertThat(restored.lastSourceSequences()).isEqualTo(sourceSequences);
    }

    /**
     * 遍历六产品线，用预先计算的指纹进入执行并检查结果表保留原实例；恢复后同载荷重复、改载荷冲突，再以新命令追加三使原余额到十。
     * @param product 本轮幂等检查隔离的产品线
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void preparedFingerprintIsRetainedAndConflictsSurviveSnapshot(ProductLine product) {
        // 六产品线用例中当前原命令 UUID
        UUID id = UUID.randomUUID();
        // 六产品线用例首条增加七的余额消息
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                id, product, CommandSource.GATEWAY, 7, 1, 1001, 1000, 11), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
        // 在进入执行前计算的原业务指纹，结果表须保留同一实例
        var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.of(message);
        try (/* 本轮用于提交原命令的交易运行时；try 作用域版本退出自动关闭 */ var state = new TradingCoreRuntime(product)) {
            // 当前余额控制命令执行或续步得到的响应
            var response = state.applyDecodedCommand(message, 1000, 1, null, false, fingerprint);
            // 最多五秒等直接控制命令完成的单调时钟截止值
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (state.directCommand.active()) {
                response = state.directCommand.poll(state);
                if (System.nanoTime() >= deadline) throw new AssertionError("funds command did not complete");
            }
            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.resultLedger.get(id).fingerprint()).isSameAs(fingerprint);
            try (/* 从原快照恢复的独立交易运行时 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(CoreTestCompletion.applyAsynchronously(restored, message).status()).isEqualTo(ResponseStatus.DUPLICATE);
                // 同一 UUID 改成不同余额增量的冲突消息
                var changed = new CoreMessage(message.header(), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 99)));
                assertThat(restored.applyDecodedCommand(changed, 1000, 2, null, false,
                        com.surprising.aeron.protocol.CommandFingerprint.of(changed)).resultCode().name())
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
                assertThat(testBalance(restored, 1001)).isEqualTo(7);
            }
            // 新 UUID 且下一来源序号的余额加三请求
            var next = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), product, CommandSource.GATEWAY, 7, 2, 1001, 1001, 12),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 3)));
            assertThat(CoreTestCompletion.applyAsynchronously(state, next).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(testBalance(state, 1001)).isEqualTo(10);
        }
    }

    /**
     * 构造用户一千零一的现货 USDT 余额调整消息，命令 UUID、来源序号、金额及请求元数据由参数指定。
     * @param commandId 用于幂等识别的原命令 UUID
     * @param sourceSequence 网关来源实例七提供的顺序号
     * @param delta 本次 USDT 余额调整单位数
     * @param submittedAt 请求头业务提交时间
     * @param correlationId 调用方请求关联号，不构成另一份业务载荷
     */
    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta,
                                     long submittedAt, long correlationId) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                submittedAt, correlationId), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", delta)));
    }
    /**
     * 从测试导出的已提交用户状态读取 USDT 总额；用户尚不存在按零断言。
     * @param state 本测试的交易运行时，读取已提交导出状态
     * @param userId 要核对余额的用户 ID
     */
    private static long testBalance(TradingCoreRuntime state, long userId) {
        // 从已提交测试状态取得的可空用户，用于核对 USDT 总额
        var user = state.tradingState().user(userId);
        return user == null ? 0 : user.totalUnits("USDT");
    }
}
