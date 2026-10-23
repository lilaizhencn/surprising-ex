package com.surprising.aeron.service.replay;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Archive 已提交回放的只读权益投影：按产品线区分期权市值与其他衍生品未实现盈亏，检查过期/未来价格、待划转用户、超过 long/JavaScript 精度的余额及查询不改业务哈希。使用合成持仓与余额，不代表实际成交或资金结算验收。
 */
class CommittedPortfolioSnapshotTest {
    /** 固定的观察与已提交来源时间，其他标记新鲜度边界相对此值构造。 */
    private static final long NOW = 1_792_454_400_000L;

    /**
     * 逐合约类型使用同一份合成多仓及标记价一百三十，检查预期单资产权益文本、完整标记和产品线，并要求结果 Map 不可修改；具体预期分别为现货一千零二十、期权一千二百八十、反向五千六百三十五、线性一千零八十。
     * @param type 当前测试的合约类型，决定产品线、面值、持仓与结算资产
     */
    @ParameterizedTest @EnumSource(ContractType.class)
    void completeEquityUsesTheProductSettlementMath(ContractType type) {
        // 合成品种、标记价、用户余额与持仓的交易状态。
        var state = state(type, NOW, 1_000, 20);
        // 捕获的只读单资产权益投影，complete 决定能否当作完整值使用。
        var snapshot = CommittedPortfolioSnapshot.capture(state, NOW, NOW, 60_000, Set.of());
        // 用户七的权益结果，要求完整且资产 Map 不可修改。
        var user = snapshot.users().get(7L);
        assertTrue(user.complete());
        // 按合约类型选择的固定预期权益十进制文本。
        String expected = type == ContractType.SPOT ? "1020"
                : type.isOption() ? "1280" : type.isInverse() ? "5635" : "1080";
        assertEquals(expected, user.netAssetUnits().get(type.isInverse() ? "BTC" : "USDT"));
        assertEquals(type.productLine(), snapshot.productLine());
        assertThrows(UnsupportedOperationException.class, () -> user.netAssetUnits().put("BTC", "1"));
    }

    /**
     * 分别使用超过六十秒的新鲜度上限一毫秒和未来一毫秒的标记时间，要求用户 complete 为 false；本方法未移除标记价测试完全缺失的场景。
     */
    @Test void unavailableOrFutureMarksCannotPublishPartialEquity() {
        for (long /* 当前变体的过期或未来标记生成时间。 */ markTime : new long[]{NOW - 60_001, NOW + 1}) {
            // 捕获的只读单资产权益投影，complete 决定能否当作完整值使用。
            var snapshot = CommittedPortfolioSnapshot.capture(state(ContractType.LINEAR_PERPETUAL, markTime, 1000, 0), NOW, NOW, 60_000, Set.of());
            assertFalse(snapshot.users().get(7L).complete());
        }
    }

    /**
     * 在现货无持仓场景把可用与冻结均设 Long.MAX_VALUE，要求两者和以精确十进制文本返回；此输入没有非零衍生品持仓保证金，不能单靠本项证明保证金无重复计入。
     */
    @Test void balancesAreExactAboveLongAndDoubleRangesAndMarginIsNotCountedTwice() {
        // 捕获的只读单资产权益投影，complete 决定能否当作完整值使用。
        var snapshot = CommittedPortfolioSnapshot.capture(state(ContractType.SPOT, NOW, Long.MAX_VALUE, Long.MAX_VALUE), NOW, NOW, 60_000, Set.of());
        assertEquals("18446744073709551614", snapshot.users().get(7L).netAssetUnits().get("USDT"));
    }

    /**
     * 把已有用户七和尚无账户用户八都标为待划转，要求两者不可用且新用户权益表为空；再传来源时间晚于观察时刻，要求拒绝非法投影边界。
     */
    @Test void pendingTransfersMakeTheUserUnavailableAndFutureStateIsRejected() {
        // 合成品种、标记价、用户余额与持仓的交易状态。
        var state = state(ContractType.SPOT, NOW, 1000, 0);
        // 已有与未建账户用户均被标为待划转的权益投影。
        var pending = CommittedPortfolioSnapshot.capture(state, NOW, NOW, 60_000, Set.of(7L, 8L));
        assertFalse(pending.users().get(7L).complete());
        assertFalse(pending.users().get(8L).complete());
        assertTrue(pending.users().get(8L).netAssetUnits().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CommittedPortfolioSnapshot.capture(state, NOW + 1, NOW, 60_000, Set.of()));
    }

    /**
     * 在独立已提交回放中应用超过 JavaScript 安全整数的一次余额调整，查询要求精确保留单位文本，查询前后业务哈希相同；不启动实际 Archive 拉流。
     */
    @Test void queryingACommittedReplayDoesNotChangeItsBusinessState() {
        try (/* 独立已提交交易回放实例，测试直接应用一条消息，退出时关闭。 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            // 固定现货 Operations 余额调整的命令头。
            var header = CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(77, 1), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 7, 1, 7, NOW, 1);
            replay.apply(CoreMessageCodec.encode(new CoreMessage(header, TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 9007199254740993L)))), NOW, 1);
            // 读取权益前的回放业务哈希，读取后须不变。
            long before = replay.businessHash();
            assertEquals("9007199254740993", replay.portfolioSnapshot(NOW, 60_000).users().get(7L).netAssetUnits().get("USDT"));
            assertEquals(before, replay.businessHash());
        }
    }

    /**
     * 直接构造一个品种与用户余额，非现货加入两单位多仓，反向设置相应面值，期权/交割设置未来到期，再附指定标记时间。用于隔离权益投影计算，不代表真实准入与结算过程。
     * @param type 当前测试的合约类型，决定产品线、面值、持仓与结算资产
     * @param markTime 合成标记价的生成时间
     * @param available 用户结算资产可用余额单位数
     * @param locked 用户结算资产冻结余额单位数
     */
    private TradingCoreState state(ContractType type, long markTime, long available, long locked) {
        // 反向使用 BTC，其余使用 USDT 的单一测试结算资产。
        String settleAsset = type.isInverse() ? "BTC" : "USDT";
        // 具有指定合约类型、反向面值和期权参数的合成品种。
        var instrument = CoreInstrument.from(type.productLine(), new RegisterInstrumentCommand("604", "BTC", "USDT", settleAsset,
                1, 1, type.isInverse() ? 1_000_000 : 1, 100_000, 50_000, 0, 0, type.isOption() || type.isDelivery() ? NOW + 86_400_000 : 0,
                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0));
        // 现货为空，其余含两单位多仓、入场价一百及入场值二百的持仓表。
        var positions = type == ContractType.SPOT ? Map.<String, CorePositionState>of()
                : Map.of("604", new CorePositionState("604", settleAsset, 2, 100, 200, 0, 0));
        // 用户七的权益结果，要求完整且资产 Map 不可修改。
        var user = new CoreUserState(type.productLine(), 7, 1, Map.of(settleAsset, new AssetBalance(settleAsset, available, locked)), Map.of(), positions);
        // 包含标记价、指数和远期价均为一百三十的合成风险状态。
        var risk = new CoreRiskState(Map.of("604", new CoreMarkPriceState("604", 130, 130, 130, 1, markTime)), Map.of(), Map.of(), Map.of(), 1);
        return new TradingCoreState(type.productLine(), 1, Map.of(7L, user), Map.of(), Map.of("604", instrument), risk, CoreTreasuryState.empty());
    }
}
