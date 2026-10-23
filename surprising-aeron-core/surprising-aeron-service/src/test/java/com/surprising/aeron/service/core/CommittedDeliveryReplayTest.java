package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 交割现金流的已提交日志回放测试，同时向原在线运行时提交相同命令作业务哈希对照。检查现金增减来自到期损益，解冻保证金不另造一笔收益；分页、快照续接及重复命令不能重复输出现金流。
 */
class CommittedDeliveryReplayTest {
    /** 交割场景业务毫秒时间基点。 */
    private static final long TIME = 1_700_000_000_000L;
    /**
     * sequence：本用例原命令递增序号。
     * now：当前场景业务时间，交割前推进到到期后。
     */
    private long sequence, now = TIME;
    /** 与回放接收完全相同命令的在线业务对照，AfterEach 关闭。 */
    private TradingCoreRuntime reference;
    /**
     * 每个用例结束关闭原在线对照运行时，尚未创建时跳过。
     */
    @AfterEach void closeReference() { if (reference != null) reference.close(); }

    /**
     * 对 U 本位和币本位交割开出双向十份仓，挂额外单后按一百一十到期分页结算；卖方先输出负损益，恢复页间快照后买方输出正损益，同命令重复不再输出现金页且回放/在线哈希一致。
     * @param line 本轮 U 本位或币本位交割产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY"})
    void settlementCashIgnoresReleasedMarginAndSurvivesPageSnapshotAndDuplicate(ProductLine line) {
        // 首结算分页完成后保存、供另一回放实例续接的快照字节
        byte[] snapshot;
        // 本场景预期绝对交割损益，U 本位一百、币本位九
        long pnl = line == ProductLine.LINEAR_DELIVERY ? 100 : 9;
        try (/* 当前新建或从首分页快照恢复的回放资源 */ var replay = opened(line)) {
            send(replay, line, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(201, "1", CoreOrderSide.SELL,
                            111, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "delivery-resting")));
            now = TIME + 20_000;
            send(replay, line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 110, 0, 1, 0, 10)));
            // 从已提交响应中解码的本次结算进度，仅作现金页诊断上下文
            var progress = assertApplied(replay, line);
            // 本次卖方结算后导出的现金变化页
            var page = replay.deliveryPage();
            assertThat(page).as("settlement progress " + progress).isNotNull();
            assertThat(page.cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(1,
                    line == ProductLine.LINEAR_DELIVERY ? "USDT" : "BTC", -pnl, 100_000 - pnl));
            snapshot = replay.snapshot();
        }
        try (/* 当前新建或从首分页快照恢复的回放资源 */ var replay = new CommittedTradeReplay(line, snapshot)) {
            // 恢复后用于买方下一页及重复提交的同一原消息
            var message = message(line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 110, 1, 1, 0, 10)));
            // 该原买方结算消息的固定字节，重复使用以核对幂等
            byte[] encoded = CoreMessageCodec.encode(message);
            replay.apply(encoded, now, sequence * 4096);
            CoreTestCompletion.applyAsynchronously(reference, message, now, sequence * 4096);
            assertThat(replay.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
            assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(2,
                    line == ProductLine.LINEAR_DELIVERY ? "USDT" : "BTC", pnl, 100_000 + pnl));
            // 重复买方结算前回放业务哈希
            long hash = replay.businessHash();
            replay.apply(encoded, now, (sequence + 1) * 4096);
            CoreTestCompletion.applyAsynchronously(reference, message, now, (sequence + 1) * 4096);
            assertThat(replay.deliveryPage()).isNull();
            assertThat(replay.businessHash()).isEqualTo(hash);
            assertThat(replay.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
        }
    }

    /**
     * 以原入场价一百交割已开持仓，检查完成且没有现金变化页，不能把返还保证金当损益。
     * @param line 本轮 U 本位或币本位交割产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY"})
    void flatSettlementHasNoInventedCashMovement(ProductLine line) {
        try (/* 当前新建或从首分页快照恢复的回放资源 */ var replay = opened(line)) {
            now = TIME + 20_000;
            send(replay, line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 100, 0, 2, 0, 10)));
            assertThat(assertApplied(replay, line).complete()).isTrue();
            assertThat(replay.deliveryPage()).isNull();
        }
    }

    /**
     * 创建回放与在线对照，注册相同到期合约及标记价，给两用户各十万结算资产并成交双向十份仓；每步比较业务哈希。
     * @param line 本轮 U 本位或币本位交割产品线
     */
    private CommittedTradeReplay opened(ProductLine line) {
        // 当前新建或从首分页快照恢复的回放资源
        var replay = new CommittedTradeReplay(line, null);
        reference = new TradingCoreRuntime(line);
        // 按本交割产品线选出的合约类型
        var type = ContractType.valueOf(line.contractTypeCode());
        // 该合约结算资产，币本位 BTC，其余 USDT
        var asset = type.isInverse() ? "BTC" : "USDT";
        send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                        "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                        100_000, 50_000, 0, 0, TIME + 10_000, -1, 0)));
        send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
        for (/* 当前初始化的卖方一或买方二 */ long user = 1; user <= 2; user++) {
            send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000)));
            send(replay, line, CoreMessageType.PLACE_ORDER, user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                            user == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "delivery-" + user)));
        }
        return replay;
    }

    /**
     * 把同一原命令和业务时间、日志位置分别交回放及在线运行时，核对哈希一致；用户命令还要求实际 APPLIED。
     * @param replay 本轮按已提交命令执行的回放实例
     * @param line 本轮 U 本位或币本位交割产品线
     * @param type 本条原协议消息类型
     * @param user 本条原业务所属用户，系统结算为零
     * @param payload 本条业务命令的原编码载荷
     */
    private void send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] payload) {
        // 本次同时交给回放与在线的原消息
        var command = message(line, type, user, payload);
        replay.apply(CoreMessageCodec.encode(command), now, sequence * 4096);
        CoreTestCompletion.applyAsynchronously(reference, command, now, sequence * 4096);
        assertThat(replay.businessHash()).as("replay cash observation preserves online trading state for " + type).isEqualTo(reference.snapshotBusinessStateHash());
        if (user > 0) assertThat(replay.commandResult().status()).as("fixture command " + type + " code=" + replay.commandResult().code()).isEqualTo(ResponseStatus.APPLIED);
    }
    /**
     * 从回放快照构造探针运行时，按本测试当前 UUID 查已提交结果，要求 APPLIED 后解码到期结算进度。
     * @param replay 本轮按已提交命令执行的回放实例
     * @param line 本轮 U 本位或币本位交割产品线
     */
    private CoreSettlementProgressView assertApplied(CommittedTradeReplay replay, ProductLine line) {
        try (/* 从回放当前快照恢复的短期只读结果核对运行时 */ var probe = TradingCoreRuntime.fromSnapshot(line, replay.snapshot())) {
            // 按当前来源 UUID 找到的原已提交结算终态
            var result = probe.resultLedger.get(new UUID(117, sequence));
            assertThat(result).as("committed settlement result").isNotNull();
            assertThat(result.status()).as("settlement code=" + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
            return CoreSettlementProgressCodec.decode(result.responseData());
        }
    }
    /**
     * 递增本测试来源序号，按当前模拟时间和指定产品线构造原消息。
     * @param line 本轮 U 本位或币本位交割产品线
     * @param type 本条原协议消息类型
     * @param user 本条原业务所属用户，系统结算为零
     * @param payload 本条业务命令的原编码载荷
     */
    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] payload) {
        // 为本次原消息递增的来源序号，也参与 UUID、关联号及模拟位置
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(117, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, now, seq), payload);
    }
}
