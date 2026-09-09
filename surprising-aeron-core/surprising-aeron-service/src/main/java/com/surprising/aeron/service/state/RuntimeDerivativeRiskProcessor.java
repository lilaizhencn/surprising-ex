package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.RiskLaneProgress;

import com.surprising.aeron.service.state.model.CoreRiskState;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;


/** 衍生品标记价与风险命令入口；调度由 RiskScanCoordinator 负责，账户估值在所属 Lane 执行。 */
public final class RuntimeDerivativeRiskProcessor {


    private RuntimeDerivativeRiskProcessor() {
    }


    public static void applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                      Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                      RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        applyMarkPriceRuntime(command, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            applyContinuationRuntime(runtime.riskScanControl().scanBatchSize(), indexedUserIds, runtime, identities);
        }
    }

    public static void applyMarkPriceRuntime(ApplyMarkPriceCommand command, TradingRuntimeState runtime,
                                             RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk apply");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, command.symbol(), command.instrumentChangeId());
        int symbolId = identities.symbolId(instrument.symbol());
        MarkPriceRuntime current = runtime.markPrice(symbolId);
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        OptionRiskRules.requireOptionRiskPrices(instrument, command.indexPriceTicks(), command.forwardPriceTicks());
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, instrument.changeId(), command.markPriceTicks(),
                command.indexPriceTicks(), command.forwardPriceTicks(), command.priceSequence(),
                command.generatedAtEpochMillis()));
        RiskScanRuntime currentScan = runtime.riskScan(symbolId);
        long scanStart = currentScan != null && !currentScan.riskComplete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        long lastUserId = currentScan != null && !currentScan.riskComplete() ? currentScan.lastUserId() : 0;
        int accountLaneId = currentScan != null && !currentScan.riskComplete()
                ? currentScan.accountLaneId() : 0;
        boolean disabled = !runtime.riskScanControl().enabled();
        RiskScanRuntime nextScan = new RiskScanRuntime(symbolId, accountLaneId,
                command.priceSequence(), scanStart, lastUserId, disabled,
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0,
                currentScan == null ? 0 : currentScan.lastScheduledRevision());
        if (!disabled && currentScan != null && !currentScan.riskComplete() && !currentScan.laneProgress().isEmpty()) {
            // 新价格使当前用户累计值失效；保留每个 Lane 已完成用户和轮次边界。
            var progress = new java.util.ArrayList<RiskLaneProgress>(
                    currentScan.laneProgress().size());
            for (var lane : currentScan.laneProgress()) progress.add(
                    new RiskLaneProgress(lane.lastUserId(), lane.complete(),
                            0, 0, "-", 0, 0, 0, 0, 0));
            nextScan = nextScan.withLaneProgress(progress);
        }
        runtime.putRiskScan(nextScan);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
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
                Math.min(maxWork, runtime.riskScanControl().scanBatchSize()), null, indexedUserIds, identities);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return completedWork;
    }

    public static int applyContinuationRuntime(int maxWork, int symbolId, PositionUserIndex positionUsers,
                                               TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || positionUsers == null) {
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
                Math.min(maxWork, runtime.riskScanControl().scanBatchSize()), positionUsers, null, identities);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return completedWork;
    }

    /** Owner-only shared budget. Charge empty symbol visits too, so empty scans cannot form an unbounded loop. */
    public static int continueRiskBudget(int maxWork, PositionUserIndex positionUsers,
                                         TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (runtime == null || identities == null || positionUsers == null || maxWork <= 0 || maxWork > 4096) {
            throw new IllegalArgumentException("invalid risk scan budget");
        }
        runtime.assertOwner();
        if (!runtime.riskScanControl().enabled()) return 0;
        int budget = Math.min(maxWork, runtime.riskScanControl().scanBatchSize());
        int remaining = budget;
        while (remaining > 0) {
            RiskScanRuntime selected = runtime.firstRiskIncompleteScan();
            if (selected == null) break;
            // Rotate before a busy symbol consumes the command's whole budget. Saved user/position
            // cursors preserve partial work; a lone symbol can receive successive slices.
            int work = applyContinuationRuntime(Math.min(8, remaining), selected.symbolId(), positionUsers,
                    runtime, identities);
            remaining -= Math.max(1, work);
        }
        return budget - remaining;
    }

    public static void syncScanProgress(TradingCoreState source, TradingRuntimeState runtime,
                                        RuntimeIdentityRegistry identities) {
        if (source == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime risk scan synchronization");
        }
        source.riskState().scans().forEach((symbol, scan) -> runtime.putRiskScan(toRuntimeScan(
                identities.symbolId(symbol), scan)));
    }

    private static int continueScan(TradingRuntimeState runtime, CoreInstrumentState instrument,
            long priceSequence, int maxWork, PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
            RuntimeIdentityRegistry identities) {
        return RiskScanCoordinator.runSlice(maxWork, identities.symbolId(instrument.symbol()), positionUsers,
                indexedUserIds, runtime, identities);
    }

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime, String symbol, long version) {
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
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
                scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress());
    }

}
