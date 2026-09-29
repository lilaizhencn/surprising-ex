package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiquidationPriceCalculatorTest {
    private final InstrumentSnapshotCache cache = mock(InstrumentSnapshotCache.class);

    @Test
    void findsLongAndShortBoundariesAtOneTickPrecision() {
        instrument(ContractType.LINEAR_PERPETUAL);
        var calculator = new LiquidationPriceCalculator(cache, ProductLine.LINEAR_PERPETUAL);
        assertThat(calculator.price(position(1, CoreMarginMode.ISOLATED, 20, 20, 0),
                List.of())).isEqualTo(89);
        assertThat(calculator.price(position(-1, CoreMarginMode.ISOLATED, 20, 20, 0),
                List.of())).isEqualTo(109);
    }

    @Test
    void crossBoundaryIncludesOtherPositionsMaintenance() {
        instrument(ContractType.LINEAR_PERPETUAL);
        var calculator = new LiquidationPriceCalculator(cache, ProductLine.LINEAR_PERPETUAL);
        var target = position(1, CoreMarginMode.CROSS, 20, 0, 0);
        var other = position(1, CoreMarginMode.CROSS, 20, 0, 5);
        assertThat(calculator.price(target, List.of(target, other))).isEqualTo(95);
    }

    @Test
    void inverseContractUsesSettlementScaleAndInversePnl() {
        InstrumentResponse inverse = mock(InstrumentResponse.class);
        when(inverse.contractType()).thenReturn(ContractType.INVERSE_PERPETUAL);
        when(inverse.notionalMultiplierUnits()).thenReturn(100L);
        when(inverse.priceTickUnits()).thenReturn(1L);
        when(inverse.maintenanceMarginRatePpm()).thenReturn(100_000L);
        when(inverse.riskLimitBrackets()).thenReturn(List.of());
        when(cache.current(ProductLine.INVERSE_PERPETUAL, 1)).thenReturn(Optional.of(inverse));
        when(cache.scale(ProductLine.INVERSE_PERPETUAL, "BTC")).thenReturn(Optional.of(1_000L));
        var calculator = new LiquidationPriceCalculator(cache, ProductLine.INVERSE_PERPETUAL);
        assertThat(calculator.price(position(1, CoreMarginMode.ISOLATED, 200, 200, 0, "BTC"), List.of()))
                .isLessThan(100L);
        assertThat(calculator.price(position(-1, CoreMarginMode.ISOLATED, 200, 200, 0, "BTC"), List.of()))
                .isGreaterThan(100L);
    }

    @Test
    void optionHasNoSingleMarkPriceLiquidationBoundary() {
        instrument(ContractType.VANILLA_OPTION);
        var calculator = new LiquidationPriceCalculator(cache, ProductLine.LINEAR_PERPETUAL);
        assertThat(calculator.price(position(1, CoreMarginMode.ISOLATED, 20, 20, 0),
                List.of())).isNull();
    }

    private void instrument(ContractType contractType) {
        InstrumentResponse instrument = mock(InstrumentResponse.class);
        when(instrument.contractType()).thenReturn(contractType);
        when(instrument.notionalMultiplierUnits()).thenReturn(1L);
        when(instrument.priceTickUnits()).thenReturn(1L);
        when(instrument.maintenanceMarginRatePpm()).thenReturn(100_000L);
        when(instrument.riskLimitBrackets()).thenReturn(List.of());
        when(cache.current(ProductLine.LINEAR_PERPETUAL, 1)).thenReturn(Optional.of(instrument));
        when(cache.scale(ProductLine.LINEAR_PERPETUAL, "USDT")).thenReturn(Optional.of(1L));
    }

    private static CoreRiskSnapshotView position(long quantity, CoreMarginMode mode, long equity,
                                                 long margin, long maintenance) {
        return position(quantity, mode, equity, margin, maintenance, "USDT");
    }

    private static CoreRiskSnapshotView position(long quantity, CoreMarginMode mode, long equity,
                                                 long margin, long maintenance, String asset) {
        return new CoreRiskSnapshotView(1, "1", mode, CorePositionSide.NET, asset, quantity,
                100, 100, 100, margin, 1, equity, equity, 0, maintenance, 0, "NORMAL");
    }
}
