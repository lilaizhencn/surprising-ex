package com.surprising.liquidation.provider.service;

import com.surprising.instrument.api.model.RiskLimitBracket;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 渐进式阶梯分级减仓规划器 (Partial Liquidation Planner)。
 * 遵循持仓梯度（RiskLimitBrackets），计算最小安全减仓量，防止极端行情下一次性砸盘冲击深度。
 */
@Component
public class PartialLiquidationPlanner {

    /**
     * 计算应执行的分级减仓数量。
     *
     * @param currentQuantitySteps 当前总持仓数量（绝对值）
     * @param markPriceTicks 当前标记价格 ticks
     * @param notionalMultiplierUnits 名义乘数
     * @param priceTickUnits 价格 tick 单位
     * @param settleScale 结算资产精度 scale
     * @param brackets 风险限额梯度列表
     * @return 本次需平仓的数量（steps）
     */
    public long calculateReductionSteps(long currentQuantitySteps,
                                        long markPriceTicks,
                                        long notionalMultiplierUnits,
                                        long priceTickUnits,
                                        long settleScale,
                                        List<RiskLimitBracket> brackets) {
        if (currentQuantitySteps <= 0 || markPriceTicks <= 0 || brackets == null || brackets.isEmpty()) {
            return currentQuantitySteps;
        }

        // 计算当前持仓的总名义价值 notional
        long currentNotional = calculateNotional(currentQuantitySteps, markPriceTicks,
                notionalMultiplierUnits, priceTickUnits, settleScale);

        // 升序排列梯度
        List<RiskLimitBracket> sorted = brackets.stream()
                .sorted(Comparator.comparingInt(RiskLimitBracket::bracketNo))
                .toList();

        // 寻找当前处于哪一个梯度
        RiskLimitBracket currentBracket = null;
        RiskLimitBracket lowerBracket = null;

        for (int i = 0; i < sorted.size(); i++) {
            RiskLimitBracket b = sorted.get(i);
            if (currentNotional >= b.notionalFloorUnits()) {
                currentBracket = b;
                lowerBracket = i > 0 ? sorted.get(i - 1) : null;
            }
        }

        // 如果在最低档 (Tier 1) 或没有更低档，必须全额减仓
        if (lowerBracket == null || currentBracket == null || currentBracket.bracketNo() <= 1) {
            return currentQuantitySteps;
        }

        // 目标是将名义价值降到 lowerBracket 的上限 notionalCapUnits
        long targetCap = lowerBracket.notionalCapUnits();
        if (targetCap >= currentNotional) {
            return currentQuantitySteps;
        }

        // 计算目标持仓数量 targetSteps
        long unitNotional = calculateNotional(1L, markPriceTicks,
                notionalMultiplierUnits, priceTickUnits, settleScale);
        if (unitNotional <= 0) {
            return currentQuantitySteps;
        }

        long targetSteps = targetCap / unitNotional;
        if (targetSteps >= currentQuantitySteps) {
            return currentQuantitySteps;
        }

        long reduction = currentQuantitySteps - targetSteps;
        return Math.max(1L, reduction);
    }

    private long calculateNotional(long quantitySteps, long priceTicks,
                                  long notionalMultiplierUnits, long priceTickUnits, long settleScale) {
        if (priceTickUnits <= 0 || settleScale <= 0) {
            return quantitySteps * priceTicks;
        }
        return Math.multiplyExact(Math.multiplyExact(quantitySteps, priceTicks), notionalMultiplierUnits)
                / (priceTickUnits * settleScale);
    }
}
