package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 强平待办工作查询。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LiquidationQueries {

    /** 把账户 Lane 已捕获的只读动作和补亏回执归并成一页。 */
    public static CoreLiquidationWorkView workPage(ProductLine productLine,
            CoreLiquidationWorkView.Query query, long[] ids, CoreLiquidationActionView[] actionReceipts,
            CoreLiquidationWorkView.Resolution[] resolutionReceipts, CoreRiskScanContinuation continuation,
            boolean sourceComplete) {
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        long cursor = query.afterLiquidationId();
        boolean complete = sourceComplete;
        int encoded = CoreLiquidationWorkCodec.emptyWorkLength(continuation);
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            var action = actionReceipts[index];
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
            int itemBytes = Math.addExact(action == null ? 0 : CoreLiquidationWorkCodec.actionLength(action),
                    resolution == null ? 0 : CoreLiquidationWorkCodec.resolutionLength(resolution));
            if (Math.addExact(encoded, itemBytes) > query.maxBytes()) { complete = false; break; }
            encoded = Math.addExact(encoded, itemBytes);
            if (action != null) actions.add(action);
            if (resolution != null) resolutions.add(resolution);
            cursor = ids[index];
        }
        return new CoreLiquidationWorkView(productLine, cursor, complete, continuation, actions, resolutions);
    }

    /** 在已有分页结果的补亏项上填入账本推荐覆盖额，缺少推荐时为 0。 */
    public static CoreLiquidationWorkView insuranceRecommendations(CoreLiquidationWorkView work,
                                                                  java.util.Map<Long, Long> allocations) {
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>(work.resolutions().size());
        for (var value : work.resolutions()) resolutions.add(new CoreLiquidationWorkView.Resolution(
                value.liquidationId(), value.userId(), value.instrumentId(), value.asset(), value.marginMode(), value.positionSide(),
                value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                allocations.getOrDefault(value.liquidationId(), 0L), value.purpose()));
        return new CoreLiquidationWorkView(work.productLine(), work.nextCursorLiquidationId(), work.complete(),
                work.riskScanContinuation(), work.actions(), resolutions);
    }

    /** 只在执行用途携带风险续扫信息。 */
    public static CoreRiskScanContinuation continuation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        if (runtime.riskScanCount() > OperationalQueries.MAX_INDEX_SCAN) {
            throw new QueryTooLargeException();
        }
        RiskScanner.State selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}
