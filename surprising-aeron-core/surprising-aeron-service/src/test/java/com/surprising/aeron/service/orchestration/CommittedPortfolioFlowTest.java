package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 已提交回放输出的资金流水和组合资产测试。只把实际应用的余额增减提取为外部资金流，区分重试、冲突、拒绝及划转完成；跨产品、跨用户转账保留原转账身份，避免被组合统计误计为交易收益。
 */
class CommittedPortfolioFlowTest {
    /** 资金流水场景业务毫秒时间基点。 */
    private static final long NOW = 1_792_454_400_000L;
    /** 本测试原来源序号，重复提交也会使用新的模拟位置。 */
    private long sequence;

    /**
     * 检查首入账一百有流水，同消息重试、同 UUID 改载荷冲突、零调整及余额不足拒绝都无流水；实际扣十后组合净资产九十。
     */
    @Test void actualAdjustmentsExcludeRetriesConflictsZeroAndRejections() {
        try (/* 本轮现货已提交回放资源，退出自动关闭 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            // 首笔入账一百的原消息字节，用作同 UUID 重试及载荷冲突对照
            byte[] first = adjust(replay, 100);
            assertEquals("100", replay.balanceFlow().amountUnits());
            assertNull(replay.balanceFlow().transfer());
            replay.apply(first, NOW + ++sequence, sequence);
            assertNull(replay.balanceFlow());
            // 从首消息字节解码的原头部及载荷
            var original = CoreMessageCodec.decode(first);
            // 复用首消息头却把增量改成二百的冲突请求
            var conflict = new CoreMessage(original.header(), TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 200)));
            replay.apply(CoreMessageCodec.encode(conflict), NOW + ++sequence, sequence);
            assertEquals(CoreResultCode.IDEMPOTENCY_CONFLICT, replay.commandResult().code());
            assertNull(replay.balanceFlow());
            adjust(replay, 0);
            assertNull(replay.balanceFlow());
            adjust(replay, -101);
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
            adjust(replay, -10);
            assertEquals("-10", replay.balanceFlow().amountUnits());
            assertEquals("90", replay.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
        }
    }

    /**
     * 现货向 U 本位永续转一百，源/目标实际应用时立即各输出负/正流水并带同转账身份；源尚待确认时组合不完整，确认后余额九百与一百且不再新增流水。
     */
    @Test void crossProductMoveCapturesImmediateDebitAndCreditAndPreservesTransferIdentity() {
        try (/* 跨产品划转发起方的现货回放资源 */ var source = new CommittedTradeReplay(ProductLine.SPOT, null);
             /* 跨产品划转接收方的 U 本位永续回放资源 */ var target = new CommittedTradeReplay(ProductLine.LINEAR_PERPETUAL, null)) {
            adjust(source, 1000);
            // 包含两端产品、账户、用户及金额的原转账意图
            var transfer = new TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 100, "synthetic-91", "", 7, 7);
            send(source, ProductLine.SPOT, 7, CoreMessageType.TRANSFER_OUT, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("-100", source.balanceFlow().amountUnits());
            assertEquals(transfer, source.balanceFlow().transfer());
            assertFalse(source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).complete());
            // 目标首次应用的转入消息字节，后面原样重试
            byte[] incoming = send(target, ProductLine.LINEAR_PERPETUAL, 7, CoreMessageType.TRANSFER_IN,
                    TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("100", target.balanceFlow().amountUnits());
            assertEquals(transfer, target.balanceFlow().transfer());
            target.apply(incoming, NOW + ++sequence, sequence);
            assertNull(target.balanceFlow());
            send(source, ProductLine.SPOT, 7, CoreMessageType.COMPLETE_TRANSFER,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(91)));
            assertNull(source.balanceFlow());
            assertTrue(source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).complete());
            assertEquals("900", source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
            assertEquals("100", target.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
        }
    }

    /**
     * 同现货产品从用户七转给八，分别核对转出/入流水的本端用户和原转账另一端用户，确认完成不再生成现金增减。
     */
    @Test void crossUserReceiptsKeepBothUsersSoTheyCannotBecomeTradingProfit() {
        try (/* 本轮现货已提交回放资源，退出自动关闭 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            adjust(replay, 1000);
            // 包含两端产品、账户、用户及金额的原转账意图
            var transfer = new TransferFundsCommand(92, ProductLine.SPOT, ProductLine.SPOT,
                    "SPOT", "SPOT", "USDT", 100, "synthetic-92", "", 7, 8);
            send(replay, ProductLine.SPOT, 7, CoreMessageType.TRANSFER_OUT, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("-100", replay.balanceFlow().amountUnits());
            assertEquals(7, replay.balanceFlow().userId());
            assertEquals(8, replay.balanceFlow().transfer().targetUserId());
            send(replay, ProductLine.SPOT, 8, CoreMessageType.TRANSFER_IN, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals(8, replay.balanceFlow().userId());
            assertEquals(7, replay.balanceFlow().transfer().sourceUserId());
            assertEquals("100", replay.balanceFlow().amountUnits());
            send(replay, ProductLine.SPOT, 7, CoreMessageType.COMPLETE_TRANSFER,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(92)));
            assertNull(replay.balanceFlow());
        }
    }

    /**
     * 畸形余额载荷和接收用户不符的转入仍交原权威执行拒绝，两者都不被回放提取成资金流水。
     */
    @Test void malformedOrWrongRecipientCommandsStillReachTheAuthoritativeRejection() {
        try (/* 本轮现货已提交回放资源，退出自动关闭 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, 7, CoreMessageType.ADJUST_BALANCE, new byte[]{1});
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
            // 包含两端产品、账户、用户及金额的原转账意图
            var transfer = new TransferFundsCommand(93, ProductLine.SPOT, ProductLine.SPOT,
                    "SPOT", "SPOT", "USDT", 100, "synthetic-93", "", 7, 8);
            send(replay, ProductLine.SPOT, 9, CoreMessageType.TRANSFER_IN, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
        }
    }

    /**
     * 给现货用户七发送指定 USDT 调整，返回同一消息字节供重复或改载荷检查。
     * @param replay 本次实际应用命令的已提交回放实例
     * @param amount 现货用户七的 USDT 余额调整单位数
     */
    private byte[] adjust(CommittedTradeReplay replay, long amount) {
        return send(replay, ProductLine.SPOT, 7, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount)));
    }

    /**
     * 按本测试递增来源和业务时间编码原消息，交回放按该位置应用，返回编码字节供后续幂等检查。
     * @param replay 本次实际应用命令的已提交回放实例
     * @param product 本次消息唯一所属产品线
     * @param user 原消息头指定的业务用户
     * @param type 本条原资金消息类型
     * @param payload 本条原协议载荷
     */
    private byte[] send(CommittedTradeReplay replay, ProductLine product, long user, CoreMessageType type, byte[] payload) {
        // 当前新增原消息的来源序号，组成 UUID、时间及模拟位置
        long id = ++sequence;
        // 本次带原业务身份的资金消息
        var command = new CoreMessage(CoreMessageHeader.command(type, new UUID(88, id), product,
                CommandSource.OPERATIONS, 88, id, user, NOW + id, id), payload);
        // 本次原消息完整编码字节，交回放后仍可用于幂等检查
        byte[] bytes = CoreMessageCodec.encode(command);
        replay.apply(bytes, NOW + id, id);
        return bytes;
    }
}
