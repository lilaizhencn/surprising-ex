package com.surprising.aeron.service.state.query;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.risk.*;

import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeLiquidationQueryService {

    private RuntimeLiquidationQueryService() {
    }

    public static boolean isExecutable(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ExecuteLiquidationCommand command) {
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED)) return false;
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || (command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence())
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
        String instrumentId = identities.instrumentId(liquidation.symbolId());
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        String positionName = liquidation.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? instrumentId : instrumentId + ':' + liquidation.positionSide().name();
        Long positionKey = identities.findPositionKey(liquidation.userId(), positionName);
        PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
        RiskSnapshotRuntime risk = positionKey == null ? null : runtime.riskSnapshot(positionKey);
        return position != null && position.instrument() == liquidation.instrument()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    public static CoreLiquidationWorkView work(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ProductLine productLine,
            CoreLiquidationWorkView.Query query, Iterable<Long> candidateIds,
            Iterable<Long> allCandidateIds) {
        java.util.Map<Long, Long> allocations = query.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? InsuranceAllocationPolicy.allocations(runtime, identities, allCandidateIds) : java.util.Map.of();
        return work(runtime, identities, productLine, query, candidateIds, allocations);
    }

    public static CoreLiquidationWorkView work(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            ProductLine productLine, CoreLiquidationWorkView.Query query, Iterable<Long> candidateIds,
            java.util.Map<Long, Long> insuranceAllocations) {
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        CoreRiskScanContinuation continuation = continuation(runtime, identities, query.purpose());
        long nextCursor = query.afterLiquidationId();
        int encodedBytes = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                productLine, nextCursor, false, continuation, actions, resolutions)).length;
        int scanned = 0;
        boolean complete = true;
        for (Long liquidationId : candidateIds) {
            if (liquidationId == null || liquidationId <= query.afterLiquidationId()) continue;
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
                complete = false;
                break;
            }
            LiquidationRuntime value = runtime.liquidation(liquidationId);
            if (!eligible(runtime, identities, productLine, query.purpose(), value)) {
                nextCursor = liquidationId;
                continue;
            }
            if (actions.size() + resolutions.size() >= query.maxItems()) {
                complete = false;
                break;
            }
            CoreLiquidationActionView action = null;
            CoreLiquidationWorkView.Resolution resolution = null;
            String instrumentId = identities.instrumentId(value.symbolId());
            if (query.purpose() == CoreLiquidationWorkView.Purpose.EXECUTION) {
                MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
                action = new CoreLiquidationActionView(value.liquidationId(), value.userId(), instrumentId,
                        value.marginMode(), value.positionSide(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                        mark.markPriceTicks(), value.status().name(),
                        value.status() == CoreLiquidationState.Status.ORDERED ? value.nextCancelOrderId() : 0);
                actions.add(action);
            } else {
                CoreInstrument instrument = runtime.instrument(instrumentId);
                resolution = new CoreLiquidationWorkView.Resolution(value.liquidationId(), value.userId(), instrumentId,
                        instrument.settleAsset(), value.marginMode(), value.positionSide(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                        insuranceAllocations.getOrDefault(value.liquidationId(), 0L), query.purpose());
                resolutions.add(resolution);
            }
            long candidateCursor = value.liquidationId();
            CoreLiquidationWorkView emptyAtCursor = new CoreLiquidationWorkView(productLine, candidateCursor,
                    false, continuation, java.util.List.of(), java.util.List.of());
            CoreLiquidationWorkView singleItem = new CoreLiquidationWorkView(productLine, candidateCursor,
                    false, continuation, action == null ? java.util.List.of() : java.util.List.of(action),
                    resolution == null ? java.util.List.of() : java.util.List.of(resolution));
            int itemBytes = Math.subtractExact(CoreLiquidationWorkCodec.encodeWork(singleItem).length,
                    CoreLiquidationWorkCodec.encodeWork(emptyAtCursor).length);
            if (Math.addExact(encodedBytes, itemBytes) > query.maxBytes()) {
                if (action != null) actions.removeLast();
                if (resolution != null) resolutions.removeLast();
                complete = false;
                break;
            }
            encodedBytes = Math.addExact(encodedBytes, itemBytes);
            nextCursor = candidateCursor;
        }
        return new CoreLiquidationWorkView(productLine, nextCursor, complete, continuation, actions, resolutions);
    }

    /** Immutable Lane receipts retain global cursor and byte-budget semantics at the query boundary. */
    public static CoreLiquidationWorkView workPage(ProductLine productLine,
            CoreLiquidationWorkView.Query query, long[] ids, CoreLiquidationActionView[] actionReceipts,
            CoreLiquidationWorkView.Resolution[] resolutionReceipts, CoreRiskScanContinuation continuation,
            boolean sourceComplete) {
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        long cursor = query.afterLiquidationId();
        boolean complete = sourceComplete;
        int encoded = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                productLine, cursor, false, continuation, List.of(), List.of())).length;
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            var action = actionReceipts[index];
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
            int itemBytes = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                    productLine, ids[index], false, continuation,
                    action == null ? List.of() : List.of(action),
                    resolution == null ? List.of() : List.of(resolution))).length
                    - CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                    productLine, ids[index], false, continuation, List.of(), List.of())).length;
            if (Math.addExact(encoded, itemBytes) > query.maxBytes()) { complete = false; break; }
            encoded = Math.addExact(encoded, itemBytes);
            if (action != null) actions.add(action);
            if (resolution != null) resolutions.add(resolution);
            cursor = ids[index];
        }
        return new CoreLiquidationWorkView(productLine, cursor, complete, continuation, actions, resolutions);
    }

    /** 查询编码边界补齐账本推荐额，不改变索赔权威或分页选取。 */
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

    private static boolean eligible(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ProductLine productLine,
            CoreLiquidationWorkView.Purpose purpose, LiquidationRuntime value) {
        if (value == null) return false;
        boolean statusMatches = switch (purpose) {
            case EXECUTION -> value.status() == CoreLiquidationState.Status.PLANNED
                    || value.status() == CoreLiquidationState.Status.ORDERED;
            case INSURANCE -> value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED;
            case ADL -> value.status() == CoreLiquidationState.Status.ADL_REQUIRED;
        };
        if (!statusMatches) return false;
        if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
            MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
            return mark != null && mark.priceSequence() == value.triggerPriceSequence();
        }
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(value.symbolId()));
        return instrument != null && instrument == value.instrument()
                && instrument.contractType().productLine() == productLine;
    }

    public static CoreRiskScanContinuation continuation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        var scans = runtime.riskScansForSnapshot();
        if (scans.size() > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        RiskScanRuntime selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}
