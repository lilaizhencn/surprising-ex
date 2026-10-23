package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证批内同品种共享已捕获判断输入，价格后来变化也不会改旧输入；到显式清理边界后才重新捕获。测试直接使用独立批次，未把它派发到账户。
 */
class BatchDecisionCaptureTest {
    /** 本组命令固定的确定性业务毫秒时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试来源已经分配的命令序号，兼作唯一 UUID 低位。 */
    private long sequence;

    /**
     * 注册两个品种，检查同品种复用同一判断对象、异品种分开；更新价格后旧对象仍保留原值，清缓存后新捕获使用新价格，最后清批不残留上下文。
     */
    @Test void contextsAreSharedPerSymbolAndReleasedBeforeRecapturingANewPrefix() {
        try (/* 测试创建并自动关闭的 U 本位永续 Core */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            for (String /* 当前要注册和定价的品种名称 */ symbol : new String[]{"1", "2"}) {
                applied(core, CoreMessageType.REGISTER_INSTRUMENT, TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand(
                                symbol, "BTC", "USDT", "USDT", 1, 1, 1,
                                new CoreInstrumentRiskLimits(
                                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                                0, 0, 0, -1, 0), ProductLine.LINEAR_PERPETUAL));
                mark(core, symbol, 100, 1);
            }
            // 独立的两项容量批次，仅用于检查配置捕获，未派发
            var batch = new OrderBatchPending(2, core.responseArena());
            batch.clusterTimestamp = TIME;
            // 品种一首次捕获的判断对象，后续价格更新不能改它
            var first = core.batches().prepareBatchDecision(batch, "1");
            // 品种二独立捕获的判断对象
            var second = core.batches().prepareBatchDecision(batch, "2");
            assertThat(core.batches().prepareBatchDecision(batch, "1")).isSameAs(first);
            assertThat(second).isNotSameAs(first);
            assertThat(batch.preparedSymbols).containsExactly("1", "2");
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);

            // 该独立批次没有派发；更新价格用来证明已捕获输入会保留到显式清理边界。
            mark(core, "1", 200, 2);
            assertThat(core.batches().prepareBatchDecision(batch, "1")).isSameAs(first);
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);
            batch.clearPreparedDecisionCache();
            assertThat(batch.preparedSymbols).isEmpty();
            assertThat(batch.preparedContextDecisions).containsOnlyNulls();
            // 清空本批判断前缀后重新捕获的品种一对象
            var next = core.batches().prepareBatchDecision(batch, "1");
            assertThat(next).isNotSameAs(first);
            assertThat(next.context().mark().markPriceTicks()).isEqualTo(200);
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);
            batch.clear();
            assertThat(batch.preparedSymbols).isEmpty();
            assertThat(batch.preparedContextDecisions).containsOnlyNulls();
        }
    }

    /**
     * 提交一个指定品种、价格及版本的标记价命令，并要求异步完成后为成功。
     * @param core 当前测试运行时
     * @param symbol 要更新价格的品种名称
     * @param price 标记价整数刻度
     * @param version 标记价版本号
     */
    private void mark(TradingCoreRuntime core, String symbol, long price, long version) {
        applied(core, CoreMessageType.APPLY_MARK_PRICE, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, price, version, TIME), core.runtimeState().productLine()));
    }

    /**
     * 为测试命令分配连续来源序号及唯一 UUID，通过既有异步完成辅助入口执行并断言 APPLIED。
     * @param core 当前测试运行时
     * @param type 测试命令的消息类别
     * @param payload 已编码的命令载荷
     */
    private void applied(TradingCoreRuntime core, CoreMessageType type, byte[] payload) {
        // 本条命令新分配的来源序号及 UUID 低位
        long id = ++sequence;
        // 带当前产品线和已编码载荷的测试 Core 命令
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(950, id), core.productLine(),
                CommandSource.GATEWAY, 950, id, 0, TIME, id), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
}
