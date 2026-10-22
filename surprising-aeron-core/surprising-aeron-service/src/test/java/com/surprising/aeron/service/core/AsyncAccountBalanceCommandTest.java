package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六产品余额与划转异步入口测试，用快照恢复后重发模拟确认丢失，区分请求 UUID 去重与划转身份重复，不执行真实网络丢包。
 */
class AsyncAccountBalanceCommandTest {
    /**
     * 跨用户、跨产品转出30并转入，恢复两边后重发同请求均为重复，再完成源记录，核对源用户70、目标用户30。
     * @param product 本轮枚举的所属产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void crossUserCrossProductTransferSurvivesLostCreditAcknowledgement(ProductLine product) {
        // 与源产品不同的目标产品，现货对应U本位永续，其余对应现货
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (/* 本例源产品运行时，退出时关闭 */ var source = new TradingCoreRuntime(product); /* 本例目标产品运行时，账户按产品隔离 */ var target = new TradingCoreRuntime(destination)) {
            CoreTestCompletion.applyAsynchronously(source, command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
            // 转出和转入共同使用的完整划转载荷
            byte[] payload = TradingCommandCodec.encodeTransferFunds(new TransferFundsCommand(8200,
                    product, destination, product.accountTypeCode(), destination.accountTypeCode(),
                    "USDT", 30, "cross-user-product", "", 11, 22));
            // 源用户的转出请求，重发时保持同一请求身份
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 正确目标用户的转入请求，恢复后重发沿用同一 UUID
            var in = new CoreMessage(CoreMessageHeader.command(CoreMessageType.TRANSFER_IN, new UUID(991, 3),
                    destination, CommandSource.GATEWAY, 991, 3, 22, 1_700_000_000_003L, 3), payload);
            assertThat(CoreTestCompletion.applyAsynchronously(target, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            try (/* 源产品快照恢复实例，用于检查重试及完成记录 */ var restoredSource = TradingCoreRuntime.fromSnapshot(product, source.snapshot(300));
                 /* 目标产品恢复实例，用于核对已入账重试不再增加资金 */ var restoredTarget = TradingCoreRuntime.fromSnapshot(destination, target.snapshot(301))) {
                assertThat(CoreTestCompletion.applyAsynchronously(restoredSource, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(CoreTestCompletion.applyAsynchronously(restoredSource,
                        command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(8200))))
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restoredSource.pendingTransfers()).isEmpty();
                assertThat(restoredSource.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                assertThat(restoredTarget.tradingState().user(22).totalUnits("USDT")).isEqualTo(30);
            }
        }
    }

    /**
     * 同用户跨产品划转，检查同请求重复、换 UUID 同划转不再扣款、两边恢复后去重与总额100；同 UUID 改金额冲突，完成源记录后余额不足转出也不遗留记录。
     * @param product 本轮枚举的所属产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void transferRetainsSeparateProductAccountsAndRecoversExactlyOnce(ProductLine product) {
        // 本例独立目标产品线
        ProductLine targetProduct = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (/* 本例源产品运行时，退出时关闭 */ var source = new TradingCoreRuntime(product); /* 本例目标产品运行时，账户按产品隔离 */ var target = new TradingCoreRuntime(targetProduct)) {
            // 源账户初始增加100的命令
            var deposit = command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(CoreTestCompletion.applyAsynchronously(source, deposit).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 本次固定金额30的划转业务参数
            var transfer = new TransferFundsCommand(7001, product, targetProduct, product.accountTypeCode(),
                    targetProduct.accountTypeCode(), "USDT", 30, "partition-transfer", "", 11L, 11L);
            // 转出和转入共同使用的完整划转载荷
            byte[] payload = TradingCommandCodec.encodeTransferFunds(transfer);
            // 源用户的转出请求，重发时保持同一请求身份
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(source, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(CoreTestCompletion.applyAsynchronously(source,
                    command(product, CoreMessageType.TRANSFER_OUT, 3, payload)).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(source.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
            try (/* 源账户恢复实例，保留一条待完成划转 */ var recovered = TradingCoreRuntime.fromSnapshot(product, source.snapshot(100))) {
                assertThat(recovered.pendingTransfers()).hasSize(1);
                // 正确目标用户的转入请求，恢复后重发沿用同一 UUID
                var in = command(targetProduct, CoreMessageType.TRANSFER_IN, 1, payload);
                assertThat(CoreTestCompletion.applyAsynchronously(target, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(CoreTestCompletion.applyAsynchronously(target, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                // 用目标已入账后快照恢复并重发，模拟确认丢失；本例没有真实网络丢包。
                try (/* 目标产品恢复实例，用于核对已入账重试不再增加资金 */ var restoredTarget = TradingCoreRuntime.fromSnapshot(targetProduct, target.snapshot(101))) {
                    assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, in).status())
                            .isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(recovered.tradingState().user(11).totalUnits("USDT")
                            + restoredTarget.tradingState().user(11).totalUnits("USDT")).isEqualTo(100);
                    // 沿用划转身份但把金额改为31的冲突参数
                    var changedTransfer = new TransferFundsCommand(7001, product, targetProduct,
                            product.accountTypeCode(), targetProduct.accountTypeCode(), "USDT", 31,
                            "partition-transfer", "", 11L, 11L);
                    // 沿用原转入 UUID 而改变正文的请求，检查指纹冲突
                    var conflict = command(targetProduct, CoreMessageType.TRANSFER_IN, 1,
                            TradingCommandCodec.encodeTransferFunds(changedTransfer));
                    assertThat(CoreTestCompletion.applyAsynchronously(restoredTarget, conflict).resultCode())
                            .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                    assertThat(restoredTarget.tradingState().user(11).totalUnits("USDT")).isEqualTo(30);
                }
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(7001))))
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(recovered.pendingTransfers()).isEmpty();
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")
                        + target.tradingState().user(11).totalUnits("USDT")).isEqualTo(100);
                // 试图从剩余70中转出71的新划转
                var tooMuch = new TransferFundsCommand(7002, product, targetProduct, product.accountTypeCode(),
                        targetProduct.accountTypeCode(), "USDT", 71, "insufficient-transfer", "", 11L, 11L);
                assertThat(CoreTestCompletion.applyAsynchronously(recovered,
                        command(product, CoreMessageType.TRANSFER_OUT, 5, TradingCommandCodec.encodeTransferFunds(tooMuch)))
                        .resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(recovered.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                assertThat(recovered.pendingTransfers()).isEmpty();
            }
        }
    }

    /**
     * 同产品用户11转给22，经两次恢复重发转入和转出不重复记账，完成后核对70及30，并拒绝用错误收款用户入账。
     * @param product 本轮枚举的所属产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void differentUsersTransferWithinOneProductAndRecoverWithoutDoubleCredit(ProductLine product) {
        try (/* 本例实际余额或同产品划转运行时 */ var state = new TradingCoreRuntime(product)) {
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(product, CoreMessageType.ADJUST_BALANCE, 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))))
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 本次固定金额30的划转业务参数
            var transfer = new TransferFundsCommand(8100, product, product, product.accountTypeCode(),
                    product.accountTypeCode(), "USDT", 30, "user-transfer", "", 11, 22);
            // 转出和转入共同使用的完整划转载荷
            byte[] payload = TradingCommandCodec.encodeTransferFunds(transfer);
            // 源用户的转出请求，重发时保持同一请求身份
            var out = command(product, CoreMessageType.TRANSFER_OUT, 2, payload);
            assertThat(CoreTestCompletion.applyAsynchronously(state, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            try (/* 当前快照恢复实例，检查原状态与重试 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot(200))) {
                // 正确目标用户的转入请求，恢复后重发沿用同一 UUID
                var in = new CoreMessage(CoreMessageHeader.command(CoreMessageType.TRANSFER_IN, new UUID(991, 3),
                        product, CommandSource.GATEWAY, 991, 3, 22, 1_700_000_000_003L, 3), payload);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, in).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                try (/* 转入后再次恢复的实例，核对两侧请求去重及最终余额 */ var recoveredAgain = TradingCoreRuntime.fromSnapshot(product, restored.snapshot(201))) {
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, in).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    // 完成源端待转记录的命令
                    var complete = command(product, CoreMessageType.COMPLETE_TRANSFER, 4,
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(8100)));
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, complete).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(recoveredAgain.pendingTransfers()).isEmpty();
                    assertThat(recoveredAgain.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                    assertThat(recoveredAgain.tradingState().user(22).totalUnits("USDT")).isEqualTo(30);
                    // 沿用原划转正文但请求用户仍为11的错误转入
                    var wrongRecipient = command(product, CoreMessageType.TRANSFER_IN, 5, payload);
                    assertThat(CoreTestCompletion.applyAsynchronously(recoveredAgain, wrongRecipient).resultCode())
                            .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                    assertThat(recoveredAgain.tradingState().user(11).totalUnits("USDT")).isEqualTo(70);
                }
            }
        }
    }

    /**
     * 按给定序号生成固定来源、用户11的测试命令，UUID 和业务时间可重复构造。
     * @param product 本轮枚举的所属产品线
     * @param type 测试协议命令类型
     * @param sequence 本次固定命令及来源序号
     * @param payload 本次原始命令载荷
     */
    private static CoreMessage command(ProductLine product, CoreMessageType type, long sequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(991, sequence), product,
                CommandSource.OPERATIONS, 991, sequence, 11, 1_700_000_000_000L + sequence, sequence), payload);
    }

    /**
     * 两个已激活实例都走同一异步帮助入口，比较五次余额调整状态和结果码，第三及第五次拒绝；检查首实例没有取得 Owner 账户访问权、终余额80及恢复哈希。这里不是独立串行模型对照。
     * @param product 本轮枚举的所属产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void accountAdjustmentAndCleanRejectionDoNotBorrowUnrelatedLanes(ProductLine product) throws Exception {
        try (/* 本例实际余额或同产品划转运行时 */ var state = new TradingCoreRuntime(product); /* 第二个已激活运行时，实际同样调用 applyAsynchronously，并非独立串行算法 */ var serial = new TradingCoreRuntime(product)) {
            state.activate(); serial.activate();
            // 余额调整目标用户11
            long user = 11;
            // 依次100、负40、负61、20及long最大值的调整输入
            long[] deltas = {100, -40, -61, 20, Long.MAX_VALUE};
            for (/* 当前调整输入下标，决定预期第三和第五次拒绝 */ int i = 0; i < deltas.length; i++) {
                // 当前输入的一开始计数序号
                long seq = i + 1;
                // 当前输入对应的完整余额请求
                var command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                        new UUID(817, seq), product, CommandSource.OPERATIONS, 817, seq, user,
                        1_700_000_000_000L + seq, seq),
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", deltas[i])));
                // 首实例异步完成的响应
                var actual = CoreTestCompletion.applyAsynchronously(state, command);
                // 第二个同入口实例的响应，仅用于一致性比较
                var expected = CoreTestCompletion.applyAsynchronously(serial, command);
                assertThat(actual.commandStatus()).isEqualTo(expected.commandStatus())
                        .isEqualTo(i == 2 || i == 4 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(actual.resultCode()).isEqualTo(expected.resultCode());
                // 反射取得首实例 ownerLaneAccess 标记，断言该轮没有借用账户所有权
                assertThat(state.runtimeState.ownerLaneAccess()).isFalse();
            }
            assertThat(state.tradingState().user(user).balances().get("USDT").availableUnits()).isEqualTo(80);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(serial.tradingState().businessStateHash());
            try (/* 当前快照恢复实例，检查原状态与重试 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            }
        }
    }
}
