package com.surprising.liquidation.provider.service;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.CoreLiquidationProjection;
import com.surprising.liquidation.provider.repository.CoreLiquidationProjectionRepository;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LiquidationService {

    private final LiquidationProperties properties;
    private final LiquidationAeronGateway aeron;
    private final CoreLiquidationProjectionRepository projections;

    public LiquidationService(LiquidationProperties properties, LiquidationAeronGateway aeron,
                              CoreLiquidationProjectionRepository projections) {
        this.properties = properties;
        this.aeron = aeron;
        this.projections = projections;
    }

    public WorkCycle processWork() {
        if (!properties.getExecution().isEnabled()) return new WorkCycle(false, 0, 0, 0);
        var work = aeron.work(properties.getCoordinator().getWorkBatchSize());
        int applied = 0;
        int obsolete = 0;
        long feeRatePpm = properties.getExecution().getLiquidationFeeRatePpm();
        for (CoreLiquidationActionView action : work.actions()) {
            CoreResultCode result = aeron.execute(action, feeRatePpm);
            if (result == CoreResultCode.NONE) applied++;
            else obsolete++;
        }
        if (work.riskScanPending()) {
            aeron.continueRiskScan(properties.getCoordinator().getRiskScanBatchSize());
        }
        return new WorkCycle(work.riskScanPending(), work.actions().size(), applied, obsolete);
    }

    public LiquidationOrderQueryResponse orders(Long userId, int limit) {
        return orders(userId, limit, null, null);
    }

    public LiquidationOrderQueryResponse orders(Long userId, int limit, String cursor, String sort) {
        int normalizedLimit = normalizeLimit(limit);
        var page = projections.page(properties.getProductLine().name(), userId, normalizedLimit, cursor, sort);
        List<LiquidationOrderResponse> rows = page.items().stream().map(LiquidationService::response).toList();
        return new LiquidationOrderQueryResponse(rows.size(), rows, page.nextCursor(), page.hasMore(),
                page.sort(), normalizedLimit);
    }

    public LiquidationOrderQueryResponse ordersByCandidate(long candidateId) {
        List<LiquidationOrderResponse> rows = projections
                .byLiquidationId(properties.getProductLine().name(), candidateId)
                .stream().map(LiquidationService::response).toList();
        return new LiquidationOrderQueryResponse(rows.size(), rows);
    }

    private static LiquidationOrderResponse response(CoreLiquidationProjection value) {
        OrderSide side = value.signedQuantitySteps() > 0 ? OrderSide.SELL : OrderSide.BUY;
        return new LiquidationOrderResponse(value.liquidationId(), value.liquidationId(), value.liquidationId(),
                value.userId(), value.symbol(), marginMode(value), positionSide(value), side,
                value.closeQuantitySteps(), 0, value.executionPriceTicks(), value.liquidationFeeRatePpm(),
                value.liquidationFeeUnits(), status(value.status()), "AERON_CORE_" + value.status(),
                value.updatedAt());
    }

    private static MarginMode marginMode(CoreLiquidationProjection value) {
        return switch (value.marginMode()) { case CROSS -> MarginMode.CROSS; case ISOLATED -> MarginMode.ISOLATED; };
    }

    private static PositionSide positionSide(CoreLiquidationProjection value) {
        return switch (value.positionSide()) {
            case NET -> PositionSide.NET;
            case LONG -> PositionSide.LONG;
            case SHORT -> PositionSide.SHORT;
        };
    }

    private static LiquidationOrderStatus status(String value) {
        return switch (value) {
            case "PLANNED", "ORDERED" -> LiquidationOrderStatus.SUBMITTED;
            case "COMPLETED", "INSURANCE_REQUIRED", "ADL_REQUIRED" -> LiquidationOrderStatus.FILLED;
            case "CANCELED" -> LiquidationOrderStatus.CANCELED;
            default -> LiquidationOrderStatus.FAILED;
        };
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be in [1,1000]");
        return limit;
    }

    public record WorkCycle(boolean riskScanContinued, int offered, int applied, int obsolete) {
    }
}
