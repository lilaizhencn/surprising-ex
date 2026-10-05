package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.query.RuntimeRiskQueryService;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRiskQueryServiceTest {
    private final RuntimeIdentityRegistry ids = new RuntimeIdentityRegistry();
    private final TradingRuntimeState runtime = new TradingRuntimeState();

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_PERPETUAL", "INVERSE_DELIVERY"})
    void revaluesPricePnlEquityMaintenanceAndStatusWithoutChangingScanState(ProductLine product) {
        setup(product);
        long key = position(product, "1", 10, CoreMarginMode.CROSS, 100, 80);
        var old = new RiskSnapshotRuntime(7, ids.symbolId("1"), CorePositionSide.NET,
                1, 900, 999, 888, 1_000_000, CoreRiskStatus.LIQUIDATION);
        runtime.putRiskSnapshot(key, old);
        long revision = runtime.revision();

        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();

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

    @Test
    void includesNewPositionsBeforeFirstScanAndAggregatesCrossWithoutIsolatedPnl() {
        var product = ProductLine.LINEAR_PERPETUAL;
        setup(product);
        position(product, "1", 10, CoreMarginMode.CROSS, 100, 80);
        position(product, "2", -5, CoreMarginMode.CROSS, 100, 80);
        position(product, "3", 10, CoreMarginMode.ISOLATED, 500, 80);
        var rows = RuntimeRiskQueryService.snapshots(runtime, ids, 7);
        assertThat(rows).hasSize(3);
        assertThat(RuntimeRiskQueryService.snapshots(runtime, ids, 0)).isEqualTo(rows);
        assertThat(runtime.riskSnapshotsForSnapshot()).isEmpty();
        // Wallet 10,000 less isolated margin 500; cross PnL -200 + 100.
        for (var row : rows.subList(0, 2)) {
            assertThat(row.walletBalanceUnits()).isEqualTo(9500);
            assertThat(row.equityUnits()).isEqualTo(9400);
            assertThat(row.marginRatioPpm()).isEqualTo(6382); // (40 + 20) / 9400
        }
        var isolated = rows.get(2);
        assertThat(isolated.equityUnits()).isEqualTo(300);
        assertThat(isolated.marginRatioPpm()).isEqualTo(133333);
        assertThat(isolated.unrealizedPnlUnits()).isEqualTo(-200);
    }

    @Test
    void optionEquityUsesPremiumValueInsteadOfAddingPnlToWallet() {
        setup(ProductLine.OPTION);
        position(ProductLine.OPTION, "1", 10, CoreMarginMode.CROSS, 0, 80);
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(-200);
        assertThat(risk.equityUnits()).isEqualTo(10800);
        assertThat(risk.maintenanceMarginUnits()).isZero();
        assertThat(risk.marginRatioPpm()).isZero();
    }

    @Test
    void zeroEquityWithMaintenanceIsLiquidatableBeforeBackgroundScan() {
        setup(ProductLine.LINEAR_PERPETUAL);
        position(ProductLine.LINEAR_PERPETUAL, "1", 10, CoreMarginMode.ISOLATED, 200, 80);
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.equityUnits()).isZero();
        assertThat(risk.marginRatioPpm()).isEqualTo(Long.MAX_VALUE);
        assertThat(risk.status()).isEqualTo("LIQUIDATION");
        assertThat(runtime.riskSnapshotsForSnapshot()).isEmpty();
    }

    @Test
    void reproducesLiveBtcPositionWithExactSettlementUnits() {
        var product = ProductLine.LINEAR_PERPETUAL;
        runtime.setMetadata(product, 1);
        runtime.putUser(new UserRuntime(product, 7, 1, CorePositionMode.ONE_WAY));
        int asset = ids.assetId("USDT"), symbol = ids.symbolId("604");
        runtime.putBalance(new BalanceRuntime(7, asset, 9_843_770_736_865L, 152_130_614_718L));
        var instrument = CoreInstrument.from(product, new com.surprising.aeron.protocol.RegisterInstrumentCommand(
                "604", com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 100_000, 10_000_000, 100_000_000,
                10_000, 5_000, 200, 500, 0, -1, 0));
        runtime.registerInstrument(instrument);
        long key = ids.positionKey(7, "604");
        runtime.putPosition(key, new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 182, 835866, 152127612, 1_688_800_000L, 152_130_614_718L));
        runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 860931, 437900, 2000));
        runtime.putRiskSnapshot(key, new RiskSnapshotRuntime(7, symbol, CorePositionSide.NET, 437798,
                10_475_817_151_583L, 479_915_800_000L, 78_463_385_000L, 7489, CoreRiskStatus.NORMAL));
        var risk = RuntimeRiskQueryService.snapshots(runtime, ids, 7).getFirst();
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(456_183_000_000L);
        assertThat(risk.equityUnits()).isEqualTo(10_452_084_351_583L);
        assertThat(risk.notionalUnits()).isEqualTo(15_668_944_200_000L);
        assertThat(risk.maintenanceMarginUnits()).isEqualTo(78_344_721_000L);
        assertThat(risk.marginRatioPpm()).isEqualTo(7495);
        assertThat(risk.priceSequence()).isEqualTo(437900);
        assertThat(RuntimeRiskQueryService.snapshots(runtime, ids, 0)).containsExactly(risk);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"64,0,64", "64,3,64", "64,430,3440", "64,1000,4096", "512,3,512"})
    void scanBudgetGrowsAndShrinksWithPendingInstruments(int minimum, int pending, int expected) {
        assertThat(RiskScanCoordinator.adaptiveBudget(minimum, pending)).isEqualTo(expected);
    }

    private void setup(ProductLine product) {
        runtime.setMetadata(product, 1);
        runtime.putUser(new UserRuntime(product, 7, 1, CorePositionMode.ONE_WAY));
        runtime.putBalance(new BalanceRuntime(7, ids.assetId(CoreStateTestFixtures.settleAsset(product)), 9000, 1000));
    }

    private long position(ProductLine product, String name, long quantity, CoreMarginMode mode, long margin, long mark) {
        String asset = CoreStateTestFixtures.settleAsset(product);
        CoreInstrument instrument = CoreInstrument.from(product,
                CoreStateTestFixtures.instrument(product, name, "BTC", "USDT", asset));
        int symbol = ids.symbolId(name);
        runtime.registerInstrument(instrument);
        long key = ids.positionKey(7, name);
        runtime.putPosition(key, new PositionRuntime(7, symbol, ids.assetId(asset), mode, CorePositionSide.NET,
                instrument, quantity, 100, Math.abs(quantity) * 100, 0, margin));
        runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, mark, 100, 100, 2, 2000));
        return key;
    }
}
