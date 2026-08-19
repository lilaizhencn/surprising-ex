package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.instrument.api.math.PerpetualContractMath;

import java.util.Map;

/** Applies perpetual mark-price risk work to the owner-thread Runtime. */
public final class RuntimePerpetualRiskProcessor {

    private RuntimePerpetualRiskProcessor() {
    }

    public static TradingRuntimeState simulateMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                                        Iterable<Long> indexedUserIds,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        applyMarkPrice(before, command, indexedUserIds, runtime, identities);
        return runtime;
    }

    public static void applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                      Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                      RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        CoreInstrumentState instrument = requireInstrument(before, command.symbol(), command.instrumentVersion());
        int symbolId = identities.symbolId(instrument.symbol());
        MarkPriceRuntime current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, instrument.version(), command.markPriceTicks(),
                command.priceSequence()));
        RiskScanRuntime currentScan = runtime.riskScan(symbolId);
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        long lastUserId = currentScan != null && !currentScan.riskComplete() ? currentScan.lastUserId() : 0;
        boolean disabled = !before.riskState().scanControl().enabled();
        runtime.putRiskScan(new RiskScanRuntime(symbolId, command.priceSequence(), scanStart, lastUserId, disabled,
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0));
        if (!disabled) {
            continueScan(before, runtime, instrument, command.priceSequence(),
                    before.riskState().scanControl().scanBatchSize(), indexedUserIds, identities);
        }
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
    }

    public static TradingRuntimeState simulateContinuation(TradingCoreState before, int maxWork,
                                                           Iterable<Long> indexedUserIds,
                                                           RuntimeIdentityRegistry identities) {
        if (before == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        applyContinuation(before, maxWork, indexedUserIds, runtime, identities);
        return runtime;
    }

    public static void applyContinuation(TradingCoreState before, int maxWork,
                                         Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!before.riskState().scanControl().enabled()) return;
        CoreRiskState.RiskScan sourceScan = before.riskState().scans().values().stream()
                .filter(scan -> !scan.riskComplete()).findFirst().orElse(null);
        if (sourceScan == null) return;
        CoreInstrumentState instrument = before.instruments().get(sourceScan.symbol());
        CoreMarkPriceState mark = before.riskState().markPrices().get(sourceScan.symbol());
        if (instrument == null || mark == null || mark.priceSequence() != sourceScan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        continueScan(before, runtime, instrument, sourceScan.priceSequence(),
                Math.min(maxWork, before.riskState().scanControl().scanBatchSize()), indexedUserIds, identities);
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
    }

    public static void syncScanProgress(TradingCoreState source, TradingRuntimeState runtime,
                                        RuntimeIdentityRegistry identities) {
        if (source == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime risk scan synchronization");
        }
        source.riskState().scans().forEach((symbol, scan) -> runtime.putRiskScan(toRuntimeScan(
                identities.symbolId(symbol), scan)));
    }

    private static TradingRuntimeState continueScan(TradingCoreState source, TradingRuntimeState runtime,
                                                    CoreInstrumentState changedInstrument, long priceSequence,
                                                    int maxWork, Iterable<Long> indexedUserIds,
                                                    RuntimeIdentityRegistry identities) {
        int symbolId = identities.symbolId(changedInstrument.symbol());
        RiskScanRuntime initial = runtime.riskScan(symbolId);
        MarkPriceRuntime changedMark = runtime.markPrice(symbolId);
        if (initial == null || changedMark == null || changedMark.priceSequence() != priceSequence) {
            throw new IllegalStateException("runtime risk scan input is missing");
        }
        RiskScanRuntime progress = initial;
        int remaining = maxWork;
        while (remaining > 0 && !progress.riskComplete()) {
            CoreUserState user = progress.riskUserId() == 0
                    ? nextUser(source, indexedUserIds, progress.lastUserId())
                    : source.user(progress.riskUserId());
            if (user == null) {
                progress = progress.withRiskProgress(true, 0, 0, "-", 0,
                        0, 0, 0, 0, progress.lastUserId());
                break;
            }
            if (progress.riskUserId() == 0) {
                progress = progress.withRiskProgress(false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, progress.lastUserId());
            }
            UserPage page = processUser(source, runtime, progress, user, changedInstrument, changedMark,
                    remaining, identities);
            progress = page.scan();
            remaining -= Math.max(1, page.workUnits());
            if (page.complete()) {
                progress = progress.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
        }
        if (!progress.riskComplete() && progress.riskUserId() == 0
                && nextUser(source, indexedUserIds, progress.lastUserId()) == null) {
            progress = progress.withRiskProgress(true, 0, 0, "-", 0,
                    0, 0, 0, 0, progress.lastUserId());
        }
        if (progress.riskComplete() && initial.scanStartPriceSequence() != initial.priceSequence()) {
            progress = new RiskScanRuntime(symbolId, initial.priceSequence(), initial.priceSequence(), 0, false,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    progress.triggerComplete(), progress.triggerPhase(), progress.triggerPriceCursor(),
                    progress.triggerOrderCursor(), progress.triggerUpperId(), progress.triggerMarkPriceTicks(),
                    progress.triggerGeneratedAtEpochMillis(), 0, 0);
        }
        runtime.putRiskScan(progress);
        return runtime;
    }

    private static UserPage processUser(TradingCoreState source, TradingRuntimeState runtime,
                                        RiskScanRuntime scan, CoreUserState user,
                                        CoreInstrumentState changedInstrument, MarkPriceRuntime changedMark,
                                        int maxWork, RuntimeIdentityRegistry identities) {
        int phase = scan.riskPhase();
        String positionCursor = scan.riskPositionCursor();
        long reservationCursor = scan.riskReservationCursor();
        long unrealized = scan.riskUnrealizedPnlUnits();
        long maintenance = scan.riskMaintenanceMarginUnits();
        long isolatedMargin = scan.riskIsolatedMarginUnits();
        long isolatedReservation = scan.riskIsolatedReservationUnits();
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                Map.Entry<String, CorePositionState> entry = nextPosition(user.positions(), positionCursor);
                if (entry == null) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = entry.getKey();
                CorePositionState position = entry.getValue();
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.marginAsset().equals(changedInstrument.settleAsset())) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbol().equals(changedInstrument.symbol())) {
                        updateIsolated(runtime, user.userId(), entry.getKey(), position, changedInstrument,
                                changedMark, identities);
                    }
                    continue;
                }
                if (!position.marginAsset().equals(changedInstrument.settleAsset())) continue;
                PositionRisk risk = risk(source, runtime, position, identities);
                if (risk == null) continue;
                unrealized = Math.addExact(unrealized, risk.unrealized());
                maintenance = Math.addExact(maintenance, risk.maintenance());
                continue;
            }
            if (phase == 1) {
                Map.Entry<Long, OrderReservation> entry = nextReservation(user.reservations(), reservationCursor);
                if (entry == null) {
                    if (maintenance == 0) {
                        return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                work, true);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = entry.getKey();
                OrderReservation reservation = entry.getValue();
                work++;
                CoreOrderState order = source.order(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.asset().equals(changedInstrument.settleAsset())) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.remainingUnits());
                }
                continue;
            }
            Map.Entry<String, CorePositionState> entry = nextPosition(user.positions(), positionCursor);
            if (entry == null) {
                return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()), work, true);
            }
            positionCursor = entry.getKey();
            CorePositionState position = entry.getValue();
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || !position.marginAsset().equals(changedInstrument.settleAsset())) continue;
            PositionRisk positionRisk = risk(source, runtime, position, identities);
            if (positionRisk == null) continue;
            int assetId = identities.assetId(changedInstrument.settleAsset());
            BalanceRuntime balance = runtime.balance(user.userId(), assetId);
            long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
            long wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
            if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            putRiskAndLiquidation(runtime, user.userId(), entry.getKey(), position, positionRisk.instrument(),
                    positionRisk.priceSequence(), equity, positionRisk.unrealized(),
                    positionRisk.maintenance(), ratio, identities);
        }
        return new UserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), work, false);
    }

    private static void updateIsolated(TradingRuntimeState runtime, long userId, String positionKey,
                                       CorePositionState position, CoreInstrumentState instrument,
                                       MarkPriceRuntime mark, RuntimeIdentityRegistry identities) {
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks());
        long equity = Math.addExact(position.positionMarginUnits(), unrealized);
        long ratio = riskRatio(maintenance, equity);
        putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, identities);
    }

    private static void putRiskAndLiquidation(TradingRuntimeState runtime, long userId, String positionKey,
                                              CorePositionState position, CoreInstrumentState instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, RuntimeIdentityRegistry identities) {
        CoreRiskStatus status = CoreRiskPolicy.status(ratio);
        int symbolId = identities.symbolId(position.symbol());
        runtime.putRiskSnapshot(identities.positionKey(userId, positionKey), new RiskSnapshotRuntime(userId,
                symbolId, position.positionSide(), priceSequence, equity, unrealized, maintenance, ratio, status));
        LiquidationRuntime active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrumentVersion(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), userId, symbolId,
                        position.marginMode(), position.positionSide(), instrument.version(), priceSequence,
                        position.signedQuantitySteps(), Math.absExact(position.signedQuantitySteps()),
                        0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
            }
            return;
        }
        long liquidationId = runtime.nextLiquidationId();
        runtime.putLiquidation(new LiquidationRuntime(liquidationId, userId, symbolId, position.marginMode(),
                position.positionSide(), instrument.version(), priceSequence, position.signedQuantitySteps(),
                Math.absExact(position.signedQuantitySteps()), 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0));
        runtime.setNextLiquidationId(Math.incrementExact(liquidationId));
    }

    private static PositionRisk risk(TradingCoreState state, TradingRuntimeState runtime,
                                     CorePositionState position, RuntimeIdentityRegistry identities) {
        CoreInstrumentState instrument = state.instruments().get(position.symbol());
        MarkPriceRuntime mark = runtime.markPrice(identities.symbolId(position.symbol()));
        if (instrument == null || mark == null) return null;
        return new PositionRisk(instrument, mark.priceSequence(),
                unrealized(position, instrument, mark.markPriceTicks()),
                CoreContractMath.maintenanceMarginUnits(instrument, position.signedQuantitySteps(),
                        mark.markPriceTicks()));
    }

    private static long unrealized(CorePositionState position, CoreInstrumentState instrument, long markPriceTicks) {
        return PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), markPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    private static long riskRatio(long maintenance, long equity) {
        if (maintenance <= 0) return 0;
        if (equity <= 0) return Long.MAX_VALUE;
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        if (instrument.version() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static RiskScanRuntime toRuntimeScan(int symbolId, CoreRiskState.RiskScan scan) {
        return new RiskScanRuntime(symbolId, scan.priceSequence(), scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor());
    }

    private static CoreUserState nextUser(TradingCoreState state, Iterable<Long> indexedUserIds, long cursor) {
        Long next = null;
        Iterable<Long> candidates = indexedUserIds == null ? state.users().keySet() : indexedUserIds;
        for (Long userId : candidates) {
            if (userId != null && userId > cursor && (next == null || userId < next)) next = userId;
        }
        return next == null ? null : state.user(next);
    }

    private static Map.Entry<String, CorePositionState> nextPosition(Map<String, CorePositionState> values,
                                                                     String cursor) {
        @SuppressWarnings("unchecked")
        java.util.NavigableMap<String, CorePositionState> sorted = values instanceof java.util.NavigableMap<?, ?> map
                ? (java.util.NavigableMap<String, CorePositionState>) map : new java.util.TreeMap<>(values);
        return "-".equals(cursor) ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    private static Map.Entry<Long, OrderReservation> nextReservation(Map<Long, OrderReservation> values,
                                                                      long cursor) {
        @SuppressWarnings("unchecked")
        java.util.NavigableMap<Long, OrderReservation> sorted = values instanceof java.util.NavigableMap<?, ?> map
                ? (java.util.NavigableMap<Long, OrderReservation>) map : new java.util.TreeMap<>(values);
        return cursor == 0 ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    private record PositionRisk(CoreInstrumentState instrument, long priceSequence,
                                long unrealized, long maintenance) {
    }

    private record UserPage(RiskScanRuntime scan, int workUnits, boolean complete) {
    }
}
