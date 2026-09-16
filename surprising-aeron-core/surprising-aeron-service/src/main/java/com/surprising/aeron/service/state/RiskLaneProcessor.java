package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.NavigableSet;

/** 仅在账户所属 Lane 执行估值及已有清算状态更新，不分配全局编号或修改全局扫描游标。 */
final class RiskLaneProcessor {
    /** 每条永久 Lane 复用一份计算临时变量，不跨线程共享。 */
    private static final ThreadLocal<PositionRiskScratch> POSITION_RISK =
            ThreadLocal.withInitial(PositionRiskScratch::new);
    private RiskLaneProcessor() { }
    static Page scan(TradingRuntimeState runtime, RiskScanRuntime initial,
                                        PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
                                        CoreInstrumentState changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RuntimeIdentityRegistry identities, long expectedUserRevision, long expectedMarketRevision) {
        RiskLiquidationBatch creations = runtime.riskLiquidationBatch(maxWork);
        RiskScanRuntime progress = initial;
        int remaining = maxWork;
        long userRevision = expectedUserRevision;
        while (remaining > 0) {
            UserRuntime user = progress.riskUserId() == 0
                    ? nextUser(runtime, positionUsers, indexedUserIds, changedInstrument.symbol(),
                    progress.accountLaneId(), progress.lastUserId())
                    : runtime.user(progress.riskUserId());
            if (user == null) {
                return new Page(progress, maxWork - remaining, true, creations, userRevision);
            }
            if (progress.riskUserId() == 0 || user.revision() != userRevision
                    || expectedMarketRevision != runtime.marketRevision()) {
                progress = progress.withRiskProgress(false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, progress.lastUserId());
            }
            userRevision = user.revision();
            expectedMarketRevision = runtime.marketRevision();
            UserPage page = processUser(runtime, progress, user, changedInstrument, changedMark,
                    settleAssetId, changedSymbolId, remaining, creations, identities);
            progress = page.scan();
            int consumed = Math.max(1, page.workUnits());
            remaining -= consumed;
            if (page.complete()) {
                progress = progress.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
        }
        boolean complete = progress.riskUserId() == 0
                && nextUser(runtime, positionUsers, indexedUserIds, changedInstrument.symbol(),
                progress.accountLaneId(), progress.lastUserId()) == null;
        return new Page(progress, maxWork - remaining, complete, creations, userRevision);
    }

    private static UserPage processUser(TradingRuntimeState runtime,
                                        RiskScanRuntime scan, UserRuntime user,
                                        CoreInstrumentState changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RiskLiquidationBatch creations,
                                        RuntimeIdentityRegistry identities) {
        int phase = scan.riskPhase();
        String positionCursor = scan.riskPositionCursor();
        long reservationCursor = scan.riskReservationCursor();
        long unrealized = scan.riskUnrealizedPnlUnits();
        long maintenance = scan.riskMaintenanceMarginUnits();
        long isolatedMargin = scan.riskIsolatedMarginUnits();
        long isolatedReservation = scan.riskIsolatedReservationUnits();
        long wallet = Long.MIN_VALUE;
        PositionRiskScratch positionRisk = POSITION_RISK.get();
        // A mark-price continuation may run on the same permanent Lane thread
        // after the previous scan was invalidated. Tie the small market cache to
        // the runtime market revision so it cannot reuse an older mark.
        positionRisk.scanMarketRevision = runtime.marketRevision();
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                long positionIdentity = nextPositionKey(runtime, identities, user.userId(), positionCursor);
                if (positionIdentity == 0) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = identities.positionKey(user.userId(), positionIdentity);
                PositionRuntime position = runtime.position(positionIdentity);
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.assetId() == settleAssetId) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbolId() == changedSymbolId) {
                        updateIsolated(runtime, user.userId(), positionCursor, position,
                                changedInstrument, changedMark, creations, identities);
                    }
                    continue;
                }
                if (position.assetId() != settleAssetId) continue;
                if (!risk(runtime, position, identities, positionRisk)) continue;
                unrealized = Math.addExact(unrealized, positionRisk.equityDelta);
                maintenance = Math.addExact(maintenance, positionRisk.maintenance);
                continue;
            }
            if (phase == 1) {
                ReservationRuntime reservation = nextReservation(runtime, user.userId(), reservationCursor);
                if (reservation == null) {
                    if (maintenance == 0) {
                        return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                work, true);
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
            long positionIdentity = nextPositionKey(runtime, identities, user.userId(), positionCursor);
            if (positionIdentity == 0) {
                return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                        work, true);
            }
            positionCursor = identities.positionKey(user.userId(), positionIdentity);
            PositionRuntime position = runtime.position(positionIdentity);
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || position.assetId() != settleAssetId) continue;
            if (!risk(runtime, position, identities, positionRisk)) continue;
            // The account balance and isolated offsets are stable while this
            // Lane owns the scan. Calculate the cross wallet once per page,
            // instead of repeating the same lookup and arithmetic for every
            // cross position in the user.
            if (wallet == Long.MIN_VALUE) {
                BalanceRuntime balance = runtime.balance(user.userId(), settleAssetId);
                long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
                wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
                if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            }
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            putRiskAndLiquidation(runtime, user.userId(), positionCursor, position,
                    positionRisk.instrument, positionRisk.priceSequence, equity, positionRisk.unrealized,
                    positionRisk.maintenance, ratio, creations, identities);
        }
        return new UserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), work, false);
    }

    private static void updateIsolated(TradingRuntimeState runtime, long userId, String positionKey,
                                       PositionRuntime position, CoreInstrumentState instrument,
                                       MarkPriceRuntime mark, RiskLiquidationBatch creations,
                                       RuntimeIdentityRegistry identities) {
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        long equity = Math.addExact(position.positionMarginUnits(), equityDelta);
        long ratio = riskRatio(maintenance, equity);
        putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, creations, identities);
    }

    private static void putRiskAndLiquidation(TradingRuntimeState runtime, long userId, String positionKey,
                                              PositionRuntime position, CoreInstrumentState instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, RiskLiquidationBatch creations,
                                              RuntimeIdentityRegistry identities) {
        CoreRiskStatus status = CoreRiskPolicy.status(ratio);
        int symbolId = position.symbolId();
        long riskKey = identities.preparedPositionKey(userId, positionKey);
        RiskSnapshotRuntime previousSnapshot = runtime.riskSnapshot(riskKey);
        // A continuation can revisit a user after a bounded page or a new scan can
        // recalculate an unchanged position. Keep the existing immutable snapshot in
        // that case; this avoids both the record allocation and the change-buffer entry.
        if (previousSnapshot == null || previousSnapshot.priceSequence() != priceSequence
                || previousSnapshot.equityUnits() != equity
                || previousSnapshot.unrealizedPnlUnits() != unrealized
                || previousSnapshot.maintenanceMarginUnits() != maintenance
                || previousSnapshot.marginRatioPpm() != ratio
                || previousSnapshot.status() != status
                || previousSnapshot.positionSide() != position.positionSide()) {
            runtime.putRiskSnapshot(riskKey, new RiskSnapshotRuntime(userId, symbolId, position.positionSide(),
                    priceSequence, equity, unrealized, maintenance, ratio, status));
        }
        // Clearance owns the remaining positions at the approved price. Keep risk valuation,
        // but do not create competing liquidations between settlement chunks.
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            return;
        }
        LiquidationRuntime active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION
                || !CoreRiskPolicy.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrumentChangeId(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                long quantity = position.signedQuantitySteps();
                if (active.userId() != userId || active.symbolId() != symbolId
                        || active.marginMode() != position.marginMode()
                        || active.positionSide() != position.positionSide()
                        || active.instrumentChangeId() != instrument.changeId()
                        || active.triggerPriceSequence() != priceSequence
                        || active.signedQuantitySteps() != quantity
                        || active.closeQuantitySteps() != Math.absExact(quantity)) {
                    runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), userId, symbolId,
                            position.marginMode(), position.positionSide(), instrument.changeId(), priceSequence,
                            quantity, Math.absExact(quantity), 0, 0, 0, 0,
                            CoreLiquidationState.Status.PLANNED, 0));
                }
            }
            return;
        }
        creations.add(position, instrument.changeId(), priceSequence);
    }

    private static boolean risk(TradingRuntimeState runtime, PositionRuntime position,
                                RuntimeIdentityRegistry identities, PositionRiskScratch result) {
        int symbolId = position.symbolId();
        CoreInstrumentState instrument = result.instrument;
        MarkPriceRuntime mark = result.mark;
        if (instrument == null || mark == null || result.symbolId != symbolId
                || instrument.changeId() != position.instrumentChangeId()
                || result.marketRevision != result.scanMarketRevision
                || result.runtime != runtime) {
            instrument = runtime.instrument(identities.preparedSymbol(symbolId));
            mark = runtime.markPrice(symbolId);
            result.symbolId = symbolId;
            result.instrument = instrument;
            result.mark = mark;
            result.marketRevision = result.scanMarketRevision;
            result.runtime = runtime;
        }
        if (instrument == null || mark == null) return false;
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
        result.instrument = instrument;
        result.priceSequence = mark.priceSequence();
        result.unrealized = unrealized;
        result.equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        result.maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        return true;
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

    private static UserRuntime nextUser(TradingRuntimeState runtime, PositionUserIndex positionUsers,
                                        Iterable<Long> indexedUserIds, String symbol,
                                        int accountLaneId, long cursor) {
        if (positionUsers != null) {
            long next = positionUsers.higherUserId(symbol, accountLaneId, cursor);
            return next == 0 ? null : runtime.user(next);
        }
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

    private static long nextPositionKey(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        long userId, String cursor) {
        return runtime.nextRiskPositionKey(userId, cursor, identities);
    }

    private static ReservationRuntime nextReservation(TradingRuntimeState runtime, long userId, long cursor) {
        long id = runtime.nextRiskReservationId(userId, cursor);
        return id == 0 ? null : runtime.reservation(id);
    }

    /** 当前持仓估值的线程内临时值，处理下一持仓时覆盖。 */
    private static final class PositionRiskScratch {
        /** 本次计算使用的币对及价格序列。 */
        private CoreInstrumentState instrument;
        private MarkPriceRuntime mark;
        private int symbolId = -1;
        private long marketRevision = Long.MIN_VALUE;
        private long scanMarketRevision = Long.MIN_VALUE;
        private TradingRuntimeState runtime;
        private long priceSequence;
        /** 浮动盈亏、维持保证金及权益增量，均为结算资产整数单位。 */
        private long unrealized;
        private long maintenance;
        private long equityDelta;
    }


    /** 单次有界扫描结果；owner 收集后提交游标并为新增清算分配编号。 */
    record Page(RiskScanRuntime scan, int workUnits, boolean complete, RiskLiquidationBatch creations, long userRevision) { }
    private record UserPage(RiskScanRuntime scan, int workUnits, boolean complete) { }
}
