package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;

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
        String symbol = identities.symbol(liquidation.symbolId());
        String positionName = liquidation.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? symbol : symbol + ':' + liquidation.positionSide().name();
        Long positionKey = identities.findPositionKey(liquidation.userId(), positionName);
        PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
        RiskSnapshotRuntime risk = positionKey == null ? null : runtime.riskSnapshot(positionKey);
        return position != null && position.instrumentVersion() == liquidation.instrumentVersion()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    public static CoreLiquidationWorkView work(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ProductLine productLine,
            CoreLiquidationWorkView.Query query, Iterable<Long> candidateIds) {
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
            String symbol = identities.symbol(value.symbolId());
            if (query.purpose() == CoreLiquidationWorkView.Purpose.EXECUTION) {
                MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
                action = new CoreLiquidationActionView(value.liquidationId(), value.userId(), symbol,
                        value.marginMode(), value.positionSide(), value.instrumentVersion(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                        mark.markPriceTicks(), value.status().name(),
                        value.status() == CoreLiquidationState.Status.ORDERED ? value.nextCancelOrderId() : 0);
                actions.add(action);
            } else {
                CoreInstrumentState instrument = runtime.instrument(symbol);
                resolution = new CoreLiquidationWorkView.Resolution(value.liquidationId(), value.userId(), symbol,
                        instrument.settleAsset(), value.marginMode(), value.positionSide(), value.instrumentVersion(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(), query.purpose());
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
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(value.symbolId()));
        return instrument != null && instrument.version() == value.instrumentVersion()
                && instrument.contractType().productLine() == productLine;
    }

    private static CoreRiskScanContinuation continuation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        RiskScanRuntime selected = null;
        String selectedSymbol = null;
        var scans = runtime.riskScansForSnapshot();
        if (scans.size() > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        int[] symbolIds = scans.keySet().toArray();
        java.util.Arrays.sort(symbolIds);
        for (int symbolId : symbolIds) {
            RiskScanRuntime candidate = scans.get(symbolId);
            if (candidate != null && !candidate.riskComplete()) {
                selected = candidate;
                selectedSymbol = identities.symbol(symbolId);
                break;
            }
        }
        return selected == null ? null
                : new CoreRiskScanContinuation(selectedSymbol, selected.priceSequence(), selected.lastUserId());
    }
}
