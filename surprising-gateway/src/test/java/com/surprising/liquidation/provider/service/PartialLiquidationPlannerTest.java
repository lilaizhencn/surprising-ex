package com.surprising.liquidation.provider.service;

import com.surprising.instrument.api.model.RiskLimitBracket;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartialLiquidationPlannerTest {

    private final PartialLiquidationPlanner planner = new PartialLiquidationPlanner();

    @Test
    @DisplayName("没有梯度或空列表时全额平仓")
    void shouldFullyLiquidateWhenNoBrackets() {
        long reduction = planner.calculateReductionSteps(100L, 50000L, 1L, 1L, 1L, List.of());
        assertEquals(100L, reduction);
    }

    @Test
    @DisplayName("处于最低梯度 Tier 1 时全额平仓")
    void shouldFullyLiquidateWhenAtTier1() {
        List<RiskLimitBracket> brackets = List.of(
                new RiskLimitBracket(1, 0L, 100_000L, 100_0000L, 10_000L, 5_000L),
                new RiskLimitBracket(2, 100_000L, 500_000L, 50_0000L, 20_000L, 10_000L)
        );
        // 当前持仓 1 份，单价 50000 -> 名义价值 50000，处于 Tier 1
        long reduction = planner.calculateReductionSteps(1L, 50000L, 1L, 1L, 1L, brackets);
        assertEquals(1L, reduction);
    }

    @Test
    @DisplayName("处于 Tier 2 时阶梯减仓降至 Tier 1 阈值上限")
    void shouldPartiallyLiquidateFromTier2ToTier1() {
        List<RiskLimitBracket> brackets = List.of(
                new RiskLimitBracket(1, 0L, 100_000L, 100_0000L, 10_000L, 5_000L),
                new RiskLimitBracket(2, 100_000L, 500_000L, 50_0000L, 20_000L, 10_000L)
        );
        // 当前持仓 6 份，单价 50000 -> 名义价值 300000，处于 Tier 2
        // Tier 1 上限是 100000，对应 100000 / 50000 = 2 份
        // 故减仓量 = 6 - 2 = 4 份
        long reduction = planner.calculateReductionSteps(6L, 50000L, 1L, 1L, 1L, brackets);
        assertEquals(4L, reduction);
    }

    @Test
    @DisplayName("处于 Tier 3 时阶梯减仓降至 Tier 2 阈值上限")
    void shouldPartiallyLiquidateFromTier3ToTier2() {
        List<RiskLimitBracket> brackets = List.of(
                new RiskLimitBracket(1, 0L, 100_000L, 100_0000L, 10_000L, 5_000L),
                new RiskLimitBracket(2, 100_000L, 500_000L, 50_0000L, 20_000L, 10_000L),
                new RiskLimitBracket(3, 500_000L, 2_000_000L, 20_0000L, 50_000L, 25_000L)
        );
        // 当前持仓 20 份，单价 50000 -> 名义价值 1000000，处于 Tier 3
        // Tier 2 上限是 500000，对应 500000 / 50000 = 10 份
        // 故减仓量 = 20 - 10 = 10 份
        long reduction = planner.calculateReductionSteps(20L, 50000L, 1L, 1L, 1L, brackets);
        assertEquals(10L, reduction);
    }
}
