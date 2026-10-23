package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证独立已提交日志回放在两种永续产品上导出分页收付明细，内存快照恢复后续页，并在重复命令时不再次导出明细或改变业务哈希。该测试不启动真实 Cluster，也不写数据库历史表。
 */
class CommittedFundingReplayTest {
    /** 本测试实例递增的源序号，同时用于命令 UUID 和模拟日志位置。 */
    private long sequence;
    /** 回放输入固定使用的毫秒时间戳。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 构造两个用户相向成交，分别断言首尾页用户、完成标记及 U 本位十单位或币本位一单位的收付；两页间用快照重建回放器，重放同一尾页命令后要求无新明细且哈希不变。
     * @param line 当前参数化的 U 本位或币本位永续产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void exportsExactPaymentsAcrossPagesSnapshotRestartAndDuplicateCommand(ProductLine line) {
        // 当前产品对应的合约类型，决定具体资金费期望金额。
        var type = ContractType.valueOf(line.contractTypeCode());
        // 当前产品的结算资产，币本位为 BTC，U 本位为 USDT。
        String asset = type.isInverse() ? "BTC" : "USDT";
        // 第一页完成后保存的内存快照字节，用于重建独立回放器。
        byte[] snapshot;
        try (/* 首段输入使用并在快照保存后关闭的提交回放器。 */ var replay = new CommittedTradeReplay(line, null)) {
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, 0, -1, 0)));
            send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (/* 初始化余额并提交相向订单的当前用户编号。 */ long user = 1; user <= 2; user++) {
                send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000)));
                send(replay, line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                user == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "funding-" + user)));
            }
            send(replay, line, CoreMessageType.APPLY_FUNDING, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, 0, 1)));
            // 最近一条资金费命令导出的页明细和进度。
            var page = replay.fundingPage();
            assertThat(page).isNotNull();
            assertThat(page.progress().complete()).isFalse();
            assertThat(page.progress().nextCursorUserId()).isEqualTo(1);
            assertThat(page.payments()).hasSize(1);
            assertThat(page.payments().getFirst().userId()).isEqualTo(1);
            assertThat(page.payments().getFirst().amountUnits()).isEqualTo(type.isInverse() ? 1 : 10);
            snapshot = replay.snapshot();
        }
        try (/* 从第一页快照恢复并在结束时关闭的提交回放器。 */ var restored = new CommittedTradeReplay(line, snapshot)) {
            // 用户游标一之后的资金费尾页命令，后面原样重复输入。
            var message = message(line, CoreMessageType.APPLY_FUNDING, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, 1, 1)));
            // 尾页命令编码字节，重复输入沿用同一命令身份与载荷。
            var bytes = CoreMessageCodec.encode(message);
            restored.apply(bytes, TIME, sequence * 4096);
            // 最近一条资金费命令导出的页明细和进度。
            var page = restored.fundingPage();
            assertThat(page.progress().complete()).isTrue();
            assertThat(page.payments()).hasSize(1);
            assertThat(page.payments().getFirst().userId()).isEqualTo(2);
            assertThat(page.payments().getFirst().amountUnits()).isEqualTo(type.isInverse() ? -1 : -10);
            // 首次尾页完成后的业务哈希，用于检查重复命令不改变状态。
            long hash = restored.businessHash();
            restored.apply(bytes, TIME, (sequence + 1) * 4096);
            assertThat(restored.fundingPage()).isNull();
            assertThat(restored.businessHash()).isEqualTo(hash);
        }
    }

    /**
     * 构造下一条命令，编码后以源序号推导的固定日志位置交给提交回放器。
     * @param replay 接收本条已提交命令的独立回放器
     * @param line 当前参数化的 U 本位或币本位永续产品线
     * @param type 本次命令的协议类型
     * @param user 命令用户编号，系统业务使用零
     * @param data 已编码的业务载荷
     */
    private void send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] data) {
        // 即将编码并交给回放器的新命令。
        var command = message(line, type, user, data);
        replay.apply(CoreMessageCodec.encode(command), TIME, sequence * 4096);
    }
    /**
     * 递增源序号并构造当前产品线运营命令，使 UUID 与序号一一对应。
     * @param line 当前参数化的 U 本位或币本位永续产品线
     * @param type 本次命令的协议类型
     * @param user 命令用户编号，系统业务使用零
     * @param data 已编码的业务载荷
     */
    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] data) {
        // 本次递增的源序号，也写入 UUID 和请求头。
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(91, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, TIME, seq), data);
    }
}
