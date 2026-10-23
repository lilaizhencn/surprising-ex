package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.query.state.RuntimeRiskQueryService;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 即时风险查询测试：按当前行情重新计算持仓风险，不能回写后台扫描缓存或推进修订号，同时核对全仓逐仓、期权权益和结算整数精度。
 */
class RuntimeRiskQueryServiceTest {
    /** 本测试运行时共用的币对、资产及持仓身份映射。 */
    private final RuntimeIdentityRegistry ids = new RuntimeIdentityRegistry();
    /** 本测试直接准备账户、持仓、行情和风险缓存的运行时。 */
    private final TradingAccountRuntime runtime = new TradingAccountRuntime();

    /**
     * 在线性和反向永续、交割中放入一份故意过期的风险缓存，再按最新行情查询价格、盈亏、权益、维持保证金和等级；原缓存对象和运行时修订号应保持原值。
     * @param product 本轮要检查的产品线，决定合约类型、结算资产和盈亏公式
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_PERPETUAL", "INVERSE_DELIVERY"})
    void revaluesPricePnlEquityMaintenanceAndStatusWithoutChangingScanState(ProductLine product) {
        setup(product);
        // 用户 7 当前合约净持仓的整数键，用来关联该持仓的风险记录。
        long key = position(product, "1", 10, CoreMarginMode.CROSS, 100, 80);
        // 故意不对应当前价格的旧风险记录，查询应重新算结果，但不能回写覆盖它。
        var old = new RiskSnapshotRuntime(7, ids.symbolId("1"), CorePositionSide.NET,
                1, 900, 999, 888, 1_000_000, CoreRiskStatus.LIQUIDATION);
        runtime.putRiskSnapshot(key, old);
        // 查询前的运行时修订号，即时只读查询后必须保持相同。
        long revision = runtime.revision();

        // 当前行情下即时重算的单条风险查询结果，与后台扫描缓存分开检查。
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();

        // 本轮是否为反向合约，期望名义金额和盈亏需要采用对应公式。
        boolean inverse = product == ProductLine.INVERSE_PERPETUAL || product == ProductLine.INVERSE_DELIVERY;
        assertThat(risk.markPriceTicks()).isEqualTo(80);
        assertThat(risk.priceSequence()).isEqualTo(2);
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(inverse ? -25 : -200);
        assertThat(risk.notionalUnits()).isEqualTo(inverse ? 125 : 800);
        assertThat(risk.maintenanceMarginUnits()).isEqualTo(inverse ? 7 : 40);
        assertThat(risk.equityUnits()).isEqualTo(inverse ? 9975 : 9800);
        assertThat(risk.marginRatioPpm()).isEqualTo(inverse ? 701 : 4081);
        assertThat(risk.status()).isEqualTo("NORMAL");
        assertThat(runtime.riskSnapshot(key)).isEqualTo(old);
        assertThat(runtime.revision()).isEqualTo(revision);
    }

    /**
     * 后台尚未扫过的新持仓也应立即出现在查询里；同资产全仓共享钱包先扣逐仓保证金，只合并全仓盈亏，逐仓单独计算自己的权益和风险占比。
     */
    @Test
    void includesNewPositionsBeforeFirstScanAndAggregatesCrossWithoutIsolatedPnl() {
        // 本用例使用的线性永续产品线。
        var product = ProductLine.LINEAR_PERPETUAL;
        setup(product);
        position(product, "1", 10, CoreMarginMode.CROSS, 100, 80);
        position(product, "2", -5, CoreMarginMode.CROSS, 100, 80);
        position(product, "3", 10, CoreMarginMode.ISOLATED, 500, 80);
        // 两个全仓加一个逐仓持仓的即时查询列表，后台缓存此时仍为空。
        var rows = RuntimeRiskQueryService.snapshots(runtime, ids, 7);
        assertThat(rows).hasSize(3);
        assertThat(RuntimeRiskQueryService.snapshots(runtime, ids, 0)).isEqualTo(rows);
        assertThat(runtime.riskSnapshotsForSnapshot()).isEmpty();
        // 总钱包一万扣除逐仓保证金五百；两份全仓的浮盈亏合计为负二百加一百。
        for (var /* 当前检查的一条全仓风险行，两份全仓持仓必须共享相同钱包和组合权益。 */ row : rows.subList(0, 2)) {
            assertThat(row.walletBalanceUnits()).isEqualTo(9500);
            assertThat(row.equityUnits()).isEqualTo(9400);
            assertThat(row.marginRatioPpm()).isEqualTo(6382); // 组合维持保证金六十除以共享权益九千四百，再按百万倍保存。
        }
        // 列表中的逐仓风险行，只使用自己分配的保证金及本仓估值变化。
        var isolated = rows.get(2);
        assertThat(isolated.equityUnits()).isEqualTo(300);
        assertThat(isolated.marginRatioPpm()).isEqualTo(133333);
        assertThat(isolated.unrealizedPnlUnits()).isEqualTo(-200);
    }

    /**
     * 期权持仓查询以权利金市值计入权益，不能再按普通期货把入场后盈亏加到钱包；同时仍独立展示未实现盈亏。
     */
    @Test
    void optionEquityUsesPremiumValueInsteadOfAddingPnlToWallet() {
        setup(ProductLine.OPTION);
        position(ProductLine.OPTION, "1", 10, CoreMarginMode.CROSS, 0, 80);
        // 当前行情下即时重算的单条风险查询结果，与后台扫描缓存分开检查。
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(-200);
        assertThat(risk.equityUnits()).isEqualTo(10800);
        assertThat(risk.maintenanceMarginUnits()).isZero();
        assertThat(risk.marginRatioPpm()).isZero();
    }

    /**
     * 逐仓权益为 0 但仍需维持保证金时，即时查询就应给出最大风险占比和强平等级，不依赖后台扫描先写风险记录。
     */
    @Test
    void zeroEquityWithMaintenanceIsLiquidatableBeforeBackgroundScan() {
        setup(ProductLine.LINEAR_PERPETUAL);
        position(ProductLine.LINEAR_PERPETUAL, "1", 10, CoreMarginMode.ISOLATED, 200, 80);
        // 当前行情下即时重算的单条风险查询结果，与后台扫描缓存分开检查。
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.equityUnits()).isZero();
        assertThat(risk.marginRatioPpm()).isEqualTo(Long.MAX_VALUE);
        assertThat(risk.status()).isEqualTo("LIQUIDATION");
        assertThat(runtime.riskSnapshotsForSnapshot()).isEmpty();
    }

    /**
     * 复现一组固定 BTC 持仓样本，按最小结算整数单位逐项核对最新盈亏、权益、名义金额、维持保证金和行情序号，避免价格与金额精度混用。
     */
    @Test
    void reproducesLiveBtcPositionWithExactSettlementUnits() {
        // 本用例使用的线性永续产品线。
        var product = ProductLine.LINEAR_PERPETUAL;
        runtime.setMetadata(product, 1);
        runtime.putUser(new UserRuntime(product, 7, 1, CorePositionMode.ONE_WAY));
        // 固定 BTC 样本中 USDT 结算资产的运行时整数编号。
        // 本测试合约的运行时整数币对编号，持仓和行情以同一编号关联。
        int asset = ids.assetId("USDT"), symbol = ids.symbolId("604");
        runtime.putBalance(new BalanceRuntime(7, asset, 9_843_770_736_865L, 152_130_614_718L));
        // 本持仓的合约与金额精度配置，决定 tick、数量步数如何换成结算金额。
        var instrument = CoreInstrument.from(product, new com.surprising.aeron.protocol.RegisterInstrumentCommand(
                "604",
                "BTC", "USDT", "USDT", 100_000, 10_000_000, 100_000_000,
                10_000, 5_000, 200, 500, 0, -1, 0));
        runtime.registerInstrument(instrument);
        // 用户 7 当前合约净持仓的整数键，用来关联该持仓的风险记录。
        long key = ids.positionKey(7, "604");
        runtime.putPosition(key, new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 182, 835866, 152127612, 1_688_800_000L, 152_130_614_718L));
        runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 860931, 437900, 2000));
        runtime.putRiskSnapshot(key, new RiskSnapshotRuntime(7, symbol, CorePositionSide.NET, 437798,
                10_475_817_151_583L, 479_915_800_000L, 78_463_385_000L, 7489, CoreRiskStatus.NORMAL));
        // 当前行情下即时重算的单条风险查询结果，与后台扫描缓存分开检查。
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(456_183_000_000L);
        assertThat(risk.equityUnits()).isEqualTo(10_452_084_351_583L);
        assertThat(risk.notionalUnits()).isEqualTo(15_668_944_200_000L);
        assertThat(risk.maintenanceMarginUnits()).isEqualTo(78_344_721_000L);
        assertThat(risk.marginRatioPpm()).isEqualTo(7495);
        assertThat(risk.priceSequence()).isEqualTo(437900);
        assertThat(RuntimeRiskQueryService.snapshots(runtime, ids, 0)).containsExactly(risk);
    }

    /**
     * 用参数化边界核对自适应预算：保留配置下限，待扫币对多时按数量增长，最高封顶 4096。
     * @param minimum 配置保证的最低风险扫描工作量
     * @param pending 当前未完成风险扫描的币对数量
     * @param expected 按确定预算公式算出的期望工作量
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"64,0,64", "64,3,64", "64,430,3440", "64,1000,4096", "512,3,512"})
    void scanBudgetGrowsAndShrinksWithPendingInstruments(int minimum, int pending, int expected) {
        assertThat(RiskScanCoordinator.adaptiveBudget(minimum, pending)).isEqualTo(expected);
    }

    /**
     * 设置本轮产品线及初始修订号，创建用户 7 和该产品结算资产的一万单位总余额，其中一千冻结。
     * @param product 本轮要检查的产品线，决定合约类型、结算资产和盈亏公式
     */
    private void setup(ProductLine product) {
        runtime.setMetadata(product, 1);
        runtime.putUser(new UserRuntime(product, 7, 1, CorePositionMode.ONE_WAY));
        runtime.putBalance(new BalanceRuntime(7, ids.assetId(CoreStateTestFixtures.settleAsset(product)), 9000, 1000));
    }

    /**
     * 为用户 7 注册指定产品合约，加入入场价一百的净持仓及序号为 2 的行情，返回持仓整数键供测试放入或读取风险记录。
     * @param product 本轮要检查的产品线，决定合约类型、结算资产和盈亏公式
     * @param name 需要创建持仓的测试合约编号
     * @param quantity 带方向持仓数量步数，正数多头、负数空头
     * @param mode 本持仓使用全仓还是逐仓保证金
     * @param margin 本持仓已经分配的保证金金额，单位为结算资产最小单位
     * @param mark 本合约当前标记价格 tick
     */
    private long position(ProductLine product, String name, long quantity, CoreMarginMode mode, long margin, long mark) {
        // 按产品线选出的结算资产外部代码，用来注册合约及定位用户余额。
        String asset = CoreStateTestFixtures.settleAsset(product);
        // 本持仓的合约与金额精度配置，决定 tick、数量步数如何换成结算金额。
        CoreInstrument instrument = CoreInstrument.from(product,
                CoreStateTestFixtures.instrument(product, name, "BTC", "USDT", asset));
        // 本测试合约的运行时整数币对编号，持仓和行情以同一编号关联。
        int symbol = ids.symbolId(name);
        runtime.registerInstrument(instrument);
        // 用户 7 当前合约净持仓的整数键，用来关联该持仓的风险记录。
        long key = ids.positionKey(7, name);
        runtime.putPosition(key, new PositionRuntime(7, symbol, ids.assetId(asset), mode, CorePositionSide.NET,
                instrument, quantity, 100, Math.abs(quantity) * 100, 0, margin));
        runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, mark, 100, 100, 2, 2000));
        return key;
    }
}
