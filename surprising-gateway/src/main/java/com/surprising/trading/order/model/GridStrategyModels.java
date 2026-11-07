package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

public final class GridStrategyModels {
    private GridStrategyModels() {}

    public record CreateGridStrategyRequest(
            @NotBlank String instrumentId,
            ProductLine productLine,
            @Positive long lowerPriceTicks,
            @Positive long upperPriceTicks,
            @Min(2) @Max(100) int gridCount,
            @Positive long totalInvestmentUnits,
            boolean isArithmetic
    ) {}

    public record GridStrategyRecord(
            long strategyId,
            long userId,
            String instrumentId,
            ProductLine productLine,
            long lowerPriceTicks,
            long upperPriceTicks,
            int gridCount,
            boolean isArithmetic,
            long totalInvestmentUnits,
            String status,
            long totalProfitUnits,
            int matchedTradeCount,
            Instant createdAt
    ) {}

    public record GridLevel(
            int levelIndex,
            long priceTicks,
            String side,
            long quantitySteps,
            long orderId,
            String status
    ) {}

    public record GridStrategyDetailResponse(
            GridStrategyRecord strategy,
            List<GridLevel> levels
    ) {}
}
