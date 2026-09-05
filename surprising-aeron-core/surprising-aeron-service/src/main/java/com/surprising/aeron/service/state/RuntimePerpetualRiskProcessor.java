package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.instrument.api.math.PerpetualContractMath;

import java.util.Map;
import java.util.NavigableSet;

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
        applyMarkPriceRuntime(command, indexedUserIds, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            applyContinuationRuntime(runtime.riskScanControl().scanBatchSize(), indexedUserIds, runtime, identities);
        }
        return runtime;
    }

    public static void applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                      Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                      RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        applyMarkPriceRuntime(command, indexedUserIds, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            applyContinuationRuntime(runtime.riskScanControl().scanBatchSize(), indexedUserIds, runtime, identities);
        }
    }

    public static void applyMarkPriceRuntime(ApplyMarkPriceCommand command, Iterable<Long> indexedUserIds,
                                             TradingRuntimeState runtime,
                                             RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, command.symbol(), command.instrumentVersion());
        int symbolId = identities.symbolId(instrument.symbol());
        MarkPriceRuntime current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, instrument.version(), command.markPriceTicks(),
                command.priceSequence(), command.generatedAtEpochMillis()));
        RiskScanRuntime currentScan = runtime.riskScan(symbolId);
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        long lastUserId = currentScan != null && !currentScan.riskComplete() ? currentScan.lastUserId() : 0;
        int accountLaneId = currentScan != null && !currentScan.riskComplete()
                ? currentScan.accountLaneId() : 0;
        boolean disabled = !runtime.riskScanControl().enabled();
        runtime.putRiskScan(new RiskScanRuntime(symbolId, accountLaneId,
                command.priceSequence(), scanStart, lastUserId, disabled,
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0));
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static TradingRuntimeState simulateContinuation(TradingCoreState before, int maxWork,
                                                           Iterable<Long> indexedUserIds,
                                                           RuntimeIdentityRegistry identities) {
        if (before == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        applyContinuationRuntime(maxWork, indexedUserIds, runtime, identities);
        return runtime;
    }

    public static void applyContinuation(TradingCoreState before, int maxWork,
                                         Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        applyContinuationRuntime(maxWork, indexedUserIds, runtime, identities);
    }

    public static void applyContinuationRuntime(int maxWork, Iterable<Long> indexedUserIds,
                                                TradingRuntimeState runtime,
                                                RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || indexedUserIds == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!runtime.riskScanControl().enabled()) return;
        RiskScanRuntime sourceScan = runtime.firstRiskIncompleteScan();
        if (sourceScan == null) return;
        applyContinuationRuntime(maxWork, sourceScan.symbolId(), indexedUserIds, runtime, identities);
    }

    public static int applyContinuationRuntime(int maxWork, int symbolId, Iterable<Long> indexedUserIds,
                                               TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || indexedUserIds == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation apply");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        if (!runtime.riskScanControl().enabled()) return 0;
        RiskScanRuntime sourceScan = runtime.riskScan(symbolId);
        if (sourceScan == null || sourceScan.riskComplete()) return 0;
        String symbol = identities.symbol(sourceScan.symbolId());
        CoreInstrumentState instrument = runtime.instrument(symbol);
        MarkPriceRuntime mark = runtime.markPrice(sourceScan.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != sourceScan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        int completedWork = continueScan(runtime, instrument, sourceScan.priceSequence(),
                Math.min(maxWork, runtime.riskScanControl().scanBatchSize()), indexedUserIds, identities);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return completedWork;
    }

    public static void syncScanProgress(TradingCoreState source, TradingRuntimeState runtime,
                                        RuntimeIdentityRegistry identities) {
        if (source == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime risk scan synchronization");
        }
        source.riskState().scans().forEach((symbol, scan) -> runtime.putRiskScan(toRuntimeScan(
                identities.symbolId(symbol), scan)));
    }

    private static int continueScan(TradingRuntimeState runtime,
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
        int settleAssetId = identities.assetId(changedInstrument.settleAsset());
        int changedSymbolId = identities.symbolId(changedInstrument.symbol());
        int remaining = maxWork;
        long nextLiquidationId = runtime.nextLiquidationId();
        while (remaining > 0 && !progress.riskComplete()) {
            RiskScanRuntime currentProgress = progress;
            int currentRemaining = remaining;
            long currentNextLiquidationId = nextLiquidationId;
            LanePage page = runtime.executeRiskLane(progress.accountLaneId(),
                    () -> processLane(runtime, currentProgress, indexedUserIds, changedInstrument, changedMark,
                            settleAssetId, changedSymbolId, currentRemaining, currentNextLiquidationId, identities));
            progress = page.scan();
            nextLiquidationId = page.nextLiquidationId();
            remaining -= page.workUnits();
            if (page.complete()) {
                if (progress.accountLaneId() + 1 < runtime.topology().accountLaneCount()) {
                    progress = progress.nextAccountLane(progress.accountLaneId() + 1);
                } else {
                    progress = progress.withRiskProgress(true, 0, 0, "-", 0,
                            0, 0, 0, 0, progress.lastUserId());
                }
            }
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (progress.riskComplete() && initial.scanStartPriceSequence() != initial.priceSequence()) {
            progress = new RiskScanRuntime(symbolId, 0, initial.priceSequence(), initial.priceSequence(), 0, false,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    progress.triggerComplete(), progress.triggerPhase(), progress.triggerPriceCursor(),
                    progress.triggerOrderCursor(), progress.triggerUpperId(), progress.triggerMarkPriceTicks(),
                    progress.triggerGeneratedAtEpochMillis(), 0, 0);
        }
        runtime.putRiskScan(progress);
        return maxWork - remaining;
    }

    private static LanePage processLane(TradingRuntimeState runtime, RiskScanRuntime initial,
                                        Iterable<Long> indexedUserIds,
                                        CoreInstrumentState changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        long nextLiquidationId, RuntimeIdentityRegistry identities) {
        RiskScanRuntime progress = initial;
        int remaining = maxWork;
        while (remaining > 0) {
            UserRuntime user = progress.riskUserId() == 0
                    ? nextUser(runtime, indexedUserIds, progress.accountLaneId(), progress.lastUserId())
                    : runtime.user(progress.riskUserId());
            if (user == null) {
                return new LanePage(progress, nextLiquidationId, maxWork - remaining, true);
            }
            if (progress.riskUserId() == 0) {
                progress = progress.withRiskProgress(false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, progress.lastUserId());
            }
            UserPage page = processUser(runtime, progress, user, changedInstrument, changedMark,
                    settleAssetId, changedSymbolId, remaining, nextLiquidationId, identities);
            progress = page.scan();
            nextLiquidationId = page.nextLiquidationId();
            int consumed = Math.max(1, page.workUnits());
            remaining -= consumed;
            if (page.complete()) {
                progress = progress.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
        }
        boolean complete = progress.riskUserId() == 0
                && nextUser(runtime, indexedUserIds, progress.accountLaneId(), progress.lastUserId()) == null;
        return new LanePage(progress, nextLiquidationId, maxWork - remaining, complete);
    }

    private static UserPage processUser(TradingRuntimeState runtime,
                                        RiskScanRuntime scan, UserRuntime user,
                                        CoreInstrumentState changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        long nextLiquidationId,
                                        RuntimeIdentityRegistry identities) {
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
                PositionEntry entry = nextPosition(runtime, identities, user.userId(), positionCursor);
                if (entry == null) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = entry.key();
                PositionRuntime position = entry.position();
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.assetId() == settleAssetId) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbolId() == changedSymbolId) {
                        nextLiquidationId = updateIsolated(runtime, user.userId(), entry.key(), position,
                                changedInstrument, changedMark, nextLiquidationId, identities);
                    }
                    continue;
                }
                if (position.assetId() != settleAssetId) continue;
                PositionRisk risk = risk(runtime, position, identities);
                if (risk == null) continue;
                unrealized = Math.addExact(unrealized, risk.unrealized());
                maintenance = Math.addExact(maintenance, risk.maintenance());
                continue;
            }
            if (phase == 1) {
                ReservationRuntime reservation = nextReservation(runtime, user.userId(), reservationCursor);
                if (reservation == null) {
                    if (maintenance == 0) {
                        return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                nextLiquidationId, work, true);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = reservation.orderId();
                work++;
                OrderRuntime order = runtime.order(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.assetId() == settleAssetId) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.reservedUnits());
                }
                continue;
            }
            PositionEntry entry = nextPosition(runtime, identities, user.userId(), positionCursor);
            if (entry == null) {
                return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                        nextLiquidationId, work, true);
            }
            positionCursor = entry.key();
            PositionRuntime position = entry.position();
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || position.assetId() != settleAssetId) continue;
            PositionRisk positionRisk = risk(runtime, position, identities);
            if (positionRisk == null) continue;
            BalanceRuntime balance = runtime.balance(user.userId(), settleAssetId);
            long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
            long wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
            if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            nextLiquidationId = putRiskAndLiquidation(runtime, user.userId(), entry.key(), position,
                    positionRisk.instrument(),
                    positionRisk.priceSequence(), equity, positionRisk.unrealized(),
                    positionRisk.maintenance(), ratio, nextLiquidationId, identities);
        }
        return new UserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), nextLiquidationId, work, false);
    }

    private static long updateIsolated(TradingRuntimeState runtime, long userId, String positionKey,
                                       PositionRuntime position, CoreInstrumentState instrument,
                                       MarkPriceRuntime mark, long nextLiquidationId,
                                       RuntimeIdentityRegistry identities) {
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks());
        long equity = Math.addExact(position.positionMarginUnits(), unrealized);
        long ratio = riskRatio(maintenance, equity);
        return putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, nextLiquidationId, identities);
    }

    private static long putRiskAndLiquidation(TradingRuntimeState runtime, long userId, String positionKey,
                                              PositionRuntime position, CoreInstrumentState instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, long nextLiquidationId,
                                              RuntimeIdentityRegistry identities) {
        CoreRiskStatus status = CoreRiskPolicy.status(ratio);
        int symbolId = position.symbolId();
        runtime.putRiskSnapshot(identities.preparedPositionKey(userId, positionKey), new RiskSnapshotRuntime(userId,
                symbolId, position.positionSide(), priceSequence, equity, unrealized, maintenance, ratio, status));
        LiquidationRuntime active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrumentVersion(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return nextLiquidationId;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), userId, symbolId,
                        position.marginMode(), position.positionSide(), instrument.version(), priceSequence,
                        position.signedQuantitySteps(), Math.absExact(position.signedQuantitySteps()),
                        0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
            }
            return nextLiquidationId;
        }
        long liquidationId = nextLiquidationId;
        runtime.putLiquidation(new LiquidationRuntime(liquidationId, userId, symbolId, position.marginMode(),
                position.positionSide(), instrument.version(), priceSequence, position.signedQuantitySteps(),
                Math.absExact(position.signedQuantitySteps()), 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0));
        return Math.incrementExact(liquidationId);
    }

    private static PositionRisk risk(TradingRuntimeState runtime, PositionRuntime position,
                                     RuntimeIdentityRegistry identities) {
        CoreInstrumentState instrument = runtime.instrument(identities.preparedSymbol(position.symbolId()));
        MarkPriceRuntime mark = runtime.markPrice(position.symbolId());
        if (instrument == null || mark == null) return null;
        return new PositionRisk(instrument, mark.priceSequence(),
                unrealized(position, instrument, mark.markPriceTicks()),
                CoreContractMath.maintenanceMarginUnits(instrument, position.signedQuantitySteps(),
                        mark.markPriceTicks()));
    }

    private static long unrealized(PositionRuntime position, CoreInstrumentState instrument, long markPriceTicks) {
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

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime, String symbol, long version) {
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        if (instrument.version() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static RiskScanRuntime toRuntimeScan(int symbolId, CoreRiskState.RiskScan scan) {
        return new RiskScanRuntime(symbolId, scan.accountLaneId(), scan.priceSequence(),
                scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor());
    }

    private static UserRuntime nextUser(TradingRuntimeState runtime, Iterable<Long> indexedUserIds,
                                        int accountLaneId, long cursor) {
        if (indexedUserIds instanceof NavigableSet<?>) {
            @SuppressWarnings("unchecked")
            NavigableSet<Long> orderedUsers = (NavigableSet<Long>) indexedUserIds;
            Long next = orderedUsers.higher(cursor);
            while (next != null && runtime.topology().accountLaneId(next) != accountLaneId) {
                next = orderedUsers.higher(next);
            }
            return next == null ? null : runtime.user(next);
        }
        throw new IllegalStateException("risk user index must be ordered for online scanning");
    }

    private static PositionEntry nextPosition(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                              long userId, String cursor) {
        PositionEntry[] selected = new PositionEntry[1];
        runtime.positionKeysForUser(userId).forEach(positionId -> {
            PositionRuntime position = runtime.position(positionId);
            if (position == null) return;
            String symbol = identities.preparedSymbol(position.symbolId());
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + position.positionSide().name();
            if (!"-".equals(cursor) && key.compareTo(cursor) <= 0) return;
            if (selected[0] == null || key.compareTo(selected[0].key()) < 0) {
                selected[0] = new PositionEntry(key, position);
            }
        });
        return selected[0];
    }

    private static ReservationRuntime nextReservation(TradingRuntimeState runtime, long userId, long cursor) {
        ReservationRuntime[] selected = new ReservationRuntime[1];
        runtime.reservationIdsForUser(userId).forEach(orderId -> {
            if (orderId <= cursor || selected[0] != null && orderId >= selected[0].orderId()) return;
            selected[0] = runtime.reservation(orderId);
        });
        return selected[0];
    }

    private record PositionRisk(CoreInstrumentState instrument, long priceSequence,
                                long unrealized, long maintenance) {
    }

    private record PositionEntry(String key, PositionRuntime position) {
    }

    private record LanePage(RiskScanRuntime scan, long nextLiquidationId, int workUnits, boolean complete) {
    }

    private record UserPage(RiskScanRuntime scan, long nextLiquidationId, int workUnits, boolean complete) {
    }
}
