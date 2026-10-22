package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用实际 Core 检查近期结果淘汰后资金身份仍能防重复，覆盖现货余额及现货到线性永续转账。部分场景恢复快照，历史 V1/V2 场景只验证同用户已发布转账编码的既有等价规则，不新增兼容逻辑。
 */
class CoreFundsIdempotencyTest {

    /**
     * 冷阶段直接登记十三万一千零八十条身份，再真实入账一百；快照恢复后的重复不再入账，另一新命令还能加五十。直接登记身份不代表此前执行了对应资金命令。
     */
    @Test
    void newFundsCommandsRemainExecutableBeyondOldRetentionLimit() {
        try (/* 本场景自动关闭的真实现货 Core */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 冷阶段直接登记长期身份用的稳定指纹
            var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.fromBytes(
                    new byte[com.surprising.aeron.protocol.CommandFingerprint.LENGTH]);
            for (/* 当前直接导入身份或写入淘汰命令的循环计数 */ int i = 0; i < 131_080; i++) state.terminalRetention().retainFundsCommand(new UUID(77, i), fingerprint);
            // 真实余额入账命令，重复不得再次改变余额
            var deposit = command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(apply(state, deposit).status()).isEqualTo(ResponseStatus.APPLIED);
            try (/* 从当前 Core 快照恢复的独立运行时 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(apply(restored, deposit).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(totalUnits(restored, 1001)).isEqualTo(100);
                // 恢复后另一 UUID 的新入账命令
                var next = command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 50)));
                assertThat(apply(restored, next).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(totalUnits(restored, 1001)).isEqualTo(150);
            }
        }
    }

    /**
     * 先加五千，再用校验命令淘汰近期响应；原实例及快照恢复实例用同 UUID 新来源序号重试，都只返回重复且总额保持五千。
     */
    @Test
    void balanceAdjustmentRemainsIdempotentAfterItsCommandResultIsEvicted() {
        try (/* 本场景自动关闭的真实现货 Core */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 需要跨结果淘汰保留幂等的原入账 UUID
            UUID adjustmentId = UUID.randomUUID();
            // 原入账金额的编码字节
            byte[] payload = TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 5_000));
            assertThat(apply(state, command(CoreMessageType.ADJUST_BALANCE, adjustmentId, 1, payload)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            for (/* 淘汰近期结果时使用的新来源序号 */ long sequence = 2; sequence <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 2L; sequence++) {
                assertThat(apply(state, command(CoreMessageType.VERIFY_STATE_HASH, UUID.randomUUID(), sequence,
                        CoreMessage.EMPTY_PAYLOAD)).status()).isEqualTo(ResponseStatus.APPLIED);
            }

            // 与原转账或入账同 UUID、等价载荷的重试
            CoreMessage retry = command(CoreMessageType.ADJUST_BALANCE, adjustmentId,
                    TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 3L, payload);
            try (/* 从当前 Core 快照恢复的独立运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(apply(restored, retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(totalUnits(restored, 1001)).isEqualTo(5_000);
            }

            assertThat(apply(state, retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(totalUnits(state, 1001)).isEqualTo(5_000);
        }
    }

    /**
     * 源现货划出只扣一次，待转账经快照保留并由完成命令移除；目标永续划入只加一次，同 UUID 改金额拒绝，核对两端余额。
     */
    @Test
    void transferLifecycleIsIdempotentAndPendingStateSurvivesSnapshot() {
        // 现货到线性永续、两端同一用户的转账业务数据
        var transfer = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 250L, "transfer-7001", "allocation", 1001L, 1001L);
        // 原转账完整编码载荷
        byte[] transferPayload = TradingCommandCodec.encodeTransferFunds(transfer);
        // 源端划出 UUID
        UUID outId = UUID.randomUUID();
        try (/* 真实源现货 Core */ TradingCoreRuntime source = new TradingCoreRuntime(ProductLine.SPOT)) {
            apply(source, command(ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                    1, TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5_000))));
            // 源端需要重复执行核对的划出命令
            CoreMessage out = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, outId, 2, transferPayload);

            assertThat(apply(source, out).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(apply(source, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(totalUnits(source, 1001)).isEqualTo(4_750L);
            assertThat(source.pendingTransfers()).hasSize(1);

            try (/* 从当前 Core 快照恢复的独立运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, source.snapshot())) {
                assertThat(restored.pendingTransfers()).hasSize(1);
                // 恢复后结束待转账生命周期的独立命令
                CoreMessage complete = command(ProductLine.SPOT, CoreMessageType.COMPLETE_TRANSFER,
                        UUID.randomUUID(), 3, TradingCommandCodec.encodeCompleteTransfer(
                                new CompleteTransferCommand(transfer.transferId())));
                assertThat(apply(restored, complete).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.pendingTransfers()).isEmpty();
                assertThat(totalUnits(restored, 1001)).isEqualTo(4_750L);
            }
        }

        // 目标划入 UUID
        UUID inId = UUID.randomUUID();
        try (/* 真实目标线性永续 Core */ TradingCoreRuntime target = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 目标端原划入命令
            CoreMessage in = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 1, transferPayload);
            assertThat(apply(target, in).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(apply(target, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(totalUnits(target, 1001)).isEqualTo(250L);

            // 同一目标转账身份但金额加一的冲突数据
            var changed = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 251L, "transfer-7001", "allocation", 1001L, 1001L);
            assertThat(apply(target, command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 2, TradingCommandCodec.encodeTransferFunds(changed))).resultCode().name())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(totalUnits(target, 1001)).isEqualTo(250L);
        }
    }

    /**
     * 手工构造既有同用户 V1 编码并与显式同用户 V2 比较，检查原命令回放、近期响应淘汰及快照恢复后两种重试均不再扣款，改收款用户则冲突；目标划入也只加一次。
     */
    @Test
    void publishedTransferV1ReplaysAndV2RetryCannotDebitAgainAfterRecovery() {
        // 现货到线性永续、两端同一用户的转账业务数据
        var transfer = new TransferFundsCommand(7002, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1001);
        // 显式保存两端用户的原 V2 转账字节
        byte[] v2 = TradingCommandCodec.encodeTransferFunds(transfer);
        // 去除新增用户字段并改写版本号的既有 V1 转账字节
        byte[] v1 = new byte[v2.length - 16];
        java.nio.ByteBuffer.wrap(v1).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putLong(7002);
        System.arraycopy(v2, 28, v1, 12, v2.length - 28);
        // 历史转账场景沿用的固定完整 UUID
        UUID id = new UUID(7002, 1);
        // 既有 V1 编码的源端原划出命令
        var oldCommand = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v1);
        // 与原转账或入账同 UUID、等价载荷的重试
        var retry = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v2);
        try (/* 真实源现货 Core */ var source = new TradingCoreRuntime(ProductLine.SPOT)) {
            apply(source, command(CoreMessageType.ADJUST_BALANCE, new UUID(7002, 0), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5000))));
            // 原划出前的快照，用来模拟日志尾部回放
            byte[] beforeTail = source.snapshot();
            assertThat(apply(source, oldCommand).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(apply(source, retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
            try (/* 从划出前快照恢复并重新执行原日志的 Core */ var crashRecovered = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, beforeTail)) {
                assertThat(apply(crashRecovered, oldCommand).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(crashRecovered.stateHash()).isEqualTo(source.stateHash());
            }
            for (/* 当前直接导入身份或写入淘汰命令的循环计数 */ int i = 3; i < 150; i++) apply(source, command(CoreMessageType.ADJUST_BALANCE,
                    new UUID(7002, i), i, TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            try (/* 从当前 Core 快照恢复的独立运行时 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, source.snapshot())) {
                // 重试前保存的业务哈希，不是余额
                long before = restored.stateHash();
                assertThat(restored.pendingTransfers().get(7002L).command()).isEqualTo(transfer);
                assertThat(apply(restored, retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(apply(restored, oldCommand).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.stateHash()).isEqualTo(before);
                assertThat(totalUnits(restored, 1001)).isEqualTo(4750 + 147);
                // 同 UUID 改收款用户的冲突转账
                var wrongRecipient = new TransferFundsCommand(7002, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                        "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1002);
                assertThat(apply(restored, command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 151,
                        TradingCommandCodec.encodeTransferFunds(wrongRecipient))).resultCode().name())
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
            }
        }
        try (/* 真实目标线性永续 Core */ var target = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 目标端既有 V1 编码的原划入命令
            var inbound = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v1);
            assertThat(apply(target, inbound).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(apply(target, command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v2)).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(totalUnits(target, 1001)).isEqualTo(250);
        }
    }

    /**
     * 不执行快照恢复，直接等异步资金完成并淘汰近期结果；同用户 V1/V2 都由长期身份判重复，待转账内容与两端金额正确，改收款人不再扣款。
     */
    @Test
    void publishedTransferV1AndV2RetriesRemainEquivalentWithoutRecovery() {
        // 现货到线性永续、两端同一用户的转账业务数据
        var transfer = new TransferFundsCommand(7003, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1001);
        // 显式保存两端用户的原 V2 转账字节
        byte[] v2 = TradingCommandCodec.encodeTransferFunds(transfer);
        // 去除新增用户字段并改写版本号的既有 V1 转账字节
        byte[] v1 = new byte[v2.length - 16];
        java.nio.ByteBuffer.wrap(v1).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putLong(7003);
        System.arraycopy(v2, 28, v1, 12, v2.length - 28);
        // 历史转账场景沿用的固定完整 UUID
        UUID id = new UUID(7003, 1);
        // 不恢复场景的原 V1 划出命令
        var original = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v1);
        // 与原转账或入账同 UUID、等价载荷的重试
        var retry = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v2);
        try (/* 真实源现货 Core */ var source = new TradingCoreRuntime(ProductLine.SPOT)) {
            CoreTestCompletion.applyAsynchronously(source, command(CoreMessageType.ADJUST_BALANCE, new UUID(7003, 0), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5000))));
            assertThat(CoreTestCompletion.applyAsynchronously(source, original).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(source, retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
            for (/* 当前直接导入身份或写入淘汰命令的循环计数 */ int i = 3; i < 150; i++)
                CoreTestCompletion.applyAsynchronously(source, command(CoreMessageType.ADJUST_BALANCE, new UUID(7003, i), i,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            assertThat(source.resultLedger().get(id)).isNull();
            for (var /* 本轮原 V1 或等价 V2 重试 */ command : java.util.List.of(original, retry))
                assertThat(CoreTestCompletion.applyAsynchronously(source, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
            // 只读查询当前待完成转账列表的消息
            var pendingQuery = new CoreMessage(CoreMessageHeader.query(CoreMessageType.PENDING_TRANSFER_QUERY,
                    new UUID(7003, 9001), ProductLine.SPOT, CommandSource.OPERATIONS, 7003, 0, 0, 0, 0),
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encodeQuery(16));
            assertThat(com.surprising.aeron.protocol.CorePendingTransferCodec.decode(
                    CoreTestCompletion.querySynchronously(source, pendingQuery).data()))
                    .singleElement().satisfies(/* 当前待转账断言项或 USDT 余额项 */ value -> assertThat(value.command()).isEqualTo(transfer));
            assertThat(totalUnits(source, 1001)).isEqualTo(4750 + 147);
            // 同一 UUID 改目标用户的冲突数据
            var wrong = new TransferFundsCommand(7003, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1002);
            assertThat(CoreTestCompletion.applyAsynchronously(source,
                    command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 151,
                            TradingCommandCodec.encodeTransferFunds(wrong))).resultCode().name())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(totalUnits(source, 1001)).isEqualTo(4750 + 147);
        }
        try (/* 真实目标线性永续 Core */ var target = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            assertThat(CoreTestCompletion.applyAsynchronously(target,
                    command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v1)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(target,
                    command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v2)).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(totalUnits(target, 1001)).isEqualTo(250);
        }
    }

    /**
     * 用既有完成助手执行真实命令并等异步资金与控制工作结束，返回终态。
     * @param core 当前要执行或查询的真实 Core
     * @param message 本次等待完成的命令
     */
    private static CoreResponse apply(TradingCoreRuntime core, CoreMessage message) {
        return CoreTestCompletion.applyAsynchronously(core, message);
    }

    /**
     * 查询测试用户状态，只对 USDT 余额累加可用加冻结，不是多资产估值。
     * @param core 当前要执行或查询的真实 Core
     * @param userId 需要汇总 USDT 的用户 ID
     */
    private static long totalUnits(TradingCoreRuntime core, long userId) {
        return CoreTestCompletion.userState(core, userId).balances().stream()
                .filter(/* 当前待转账断言项或 USDT 余额项 */ value -> value.asset().equals("USDT"))
                .mapToLong(/* 当前待转账断言项或 USDT 余额项 */ value -> value.availableUnits() + value.lockedUnits()).sum();
    }

    /**
     * 为默认现货产品构造资金或校验命令，保留调用方 UUID 和来源序号。
     * @param type 本条资金或校验命令类型
     * @param commandId 本条命令 UUID，重试沿用原值
     * @param sourceSequence 调用方指定的来源序号，场景分别使用原序号或更新序号
     * @param payload 与命令类型对应的编码载荷
     */
    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence, byte[] payload) {
        return command(ProductLine.SPOT, type, commandId, sourceSequence, payload);
    }

    /**
     * 按显式产品线构造固定来源、用户和业务时间的测试命令。
     * @param productLine 当前独立 Core 所属产品线
     * @param type 本条资金或校验命令类型
     * @param commandId 本条命令 UUID，重试沿用原值
     * @param sourceSequence 调用方指定的来源序号，场景分别使用原序号或更新序号
     * @param payload 与命令类型对应的编码载荷
     */
    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence), payload);
    }
}
