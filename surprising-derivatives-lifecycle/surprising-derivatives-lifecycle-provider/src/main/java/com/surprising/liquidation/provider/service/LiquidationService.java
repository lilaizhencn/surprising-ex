package com.surprising.liquidation.provider.service;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.client.AeronLifecycleCoordinator;
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
    private final AeronLifecycleCoordinator lifecycleCoordinator = AeronLifecycleCoordinator.shared();
    public LiquidationService(LiquidationProperties properties, LiquidationAeronGateway aeron,
                              CoreLiquidationProjectionRepository projections) {
        this.properties = properties;
        this.aeron = aeron;
        this.projections = projections;
    }

    public synchronized WorkCycle processWork() {
        return lifecycleCoordinator.execute(this::processWorkInternal);
    }

    private WorkCycle processWorkInternal() {
        if (!properties.getExecution().isEnabled()) return new WorkCycle(false, 0, 0, 0, 0, 0);
        long feeRatePpm = properties.getExecution().getLiquidationFeeRatePpm();
        long cursor = 0;
        boolean riskScanContinued = false;
        int offered = 0;
        int applied = 0;
        int pending = 0;
        int obsolete = 0;
        int processedOrders = 0;
        for (int page = 0; page < properties.getCoordinator().getMaxPagesPerRun(); page++) {
            var work = aeron.work(cursor, properties.getCoordinator().getWorkBatchSize(),
                    properties.getCoordinator().getMaxWorkBytes());
            validateWork(work, cursor);
            if (work.actions().isEmpty() && !work.riskScanPending()) break;
            var result = aeron.executeBatch(work, feeRatePpm,
                    properties.getCoordinator().getRiskScanBatchSize());
            riskScanContinued |= result.riskScanContinuedUsers() > 0;
            offered = Math.addExact(offered, result.offeredActions());
            applied = Math.addExact(applied, result.appliedActions());
            pending = Math.addExact(pending, result.pendingActions());
            obsolete = Math.addExact(obsolete, result.obsoleteActions());
            processedOrders = Math.addExact(processedOrders, result.processedOrders());
            if (work.complete()) break;
            cursor = work.nextCursorLiquidationId();
        }
        return new WorkCycle(riskScanContinued, offered, applied, pending, obsolete, processedOrders);
    }

    private void validateWork(com.surprising.aeron.protocol.CoreLiquidationWorkView work, long requestedCursor) {
        if (work.productLine() != properties.getProductLine()) {
            throw new IllegalStateException("Core liquidation work ProductLine mismatch");
        }
        if (!work.resolutions().isEmpty()) {
            throw new IllegalStateException("Core liquidation execution query returned resolution work");
        }
        if (!work.complete() && work.nextCursorLiquidationId() == requestedCursor) {
            throw new IllegalStateException("Core liquidation work cursor did not advance");
        }
        long expectedCursor = work.actions().isEmpty()
                ? requestedCursor : work.actions().getLast().liquidationId();
        if (work.nextCursorLiquidationId() != expectedCursor) {
            throw new IllegalStateException("Core liquidation work cursor gap");
        }
        for (CoreLiquidationActionView action : work.actions()) {
            boolean ordered = "ORDERED".equals(action.status());
            if (!ordered && !"PLANNED".equals(action.status()) || ordered != (action.cursorOrderId() > 0)) {
                throw new IllegalStateException("Core liquidation work status/cursor mismatch");
            }
        }
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

    public record WorkCycle(boolean riskScanContinued, int offered, int applied, int pending,
                             int obsolete, int processedOrders) {
    }
}
