package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.math.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskSnapshot;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.model.RiskLaneProgress;
import com.surprising.instrument.api.math.PerpetualContractMath;

import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;

/** Executes a bounded Account Lane risk scan and materializes its risk facts. */
final class RiskScanExecution {

    private final LaneTopology topology;

    RiskScanExecution(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
    }

    TradingCoreState continueScan(TradingCoreState state, int maxUsers,
                                  PositionUserIndex positionUserIndex,
                                  LiquidationIndex liquidationIndex) {
        if (maxUsers <= 0 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid risk scan batch size");
        }
        CoreRiskScanControlView scanControl = state.riskState().scanControl();
        if (!scanControl.enabled()) return state;
        maxUsers = Math.min(maxUsers, scanControl.scanBatchSize());
        CoreRiskState.RiskScan scan = state.riskState().scans().values().stream()
                .filter(value -> !value.riskComplete()).min(java.util.Comparator
                        .comparingLong(CoreRiskState.RiskScan::lastScheduledRevision)
                        .thenComparing(CoreRiskState.RiskScan::symbol)).orElse(null);
        if (scan == null) {
            return state;
        }
        CoreInstrumentState instrument = state.instruments().get(scan.symbol());
        CoreMarkPriceState mark = state.riskState().markPrices().get(scan.symbol());
        if (instrument == null || mark == null || mark.priceSequence() != scan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        Map<String, CoreRiskSnapshot> snapshots = StateMapSupport.delta(state.riskState().snapshots());
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        long nextLiquidationId = state.riskState().nextLiquidationId();
        int laneCount = topology.accountLaneCount();
        if (!scan.laneProgress().isEmpty() && scan.laneProgress().size() != laneCount)
            throw new IllegalStateException("risk scan Lane topology differs");
        var laneScans = new CoreRiskState.RiskScan[laneCount];
        int[] allocations = new int[laneCount];
        int participants = 0;
        for (int lane = 0; lane < laneCount; lane++) {
            RiskLaneProgress cursor = scan.laneProgress().isEmpty()
                    ? (lane == scan.accountLaneId() ? riskLaneCursor(scan, scan.riskComplete())
                        : new RiskLaneProgress(0, lane < scan.accountLaneId(), 0, 0, "-", 0, 0, 0, 0, 0))
                    : scan.laneProgress().get(lane);
            if (!cursor.complete() && cursor.userId() == 0
                    && nextRiskUser(state, positionUserIndex, scan.symbol(), lane, cursor.lastUserId()) == null)
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            laneScans[lane] = riskLaneInput(scan, lane, cursor);
            if (!cursor.complete()) participants++;
        }
        int selectedCount = Math.min(participants, maxUsers);
        int assigned = 0;
        int nextLane = scan.accountLaneId();
        for (int offset = 0; offset < laneCount && assigned < selectedCount; offset++) {
            int lane = (scan.accountLaneId() + offset) % laneCount;
            if (laneScans[lane].riskComplete()) continue;
            allocations[lane] = maxUsers / selectedCount + (assigned < maxUsers % selectedCount ? 1 : 0);
            nextLane = (lane + 1) % laneCount;
            assigned++;
        }
        // 状态形式的同步入口也按 Lane 编号提交；账户风险公式仍独立计算。
        for (int lane = 0; lane < laneCount; lane++) {
            if (allocations[lane] == 0) continue;
            CoreRiskState.RiskScan laneScan = laneScans[lane];
            int remainingWork = allocations[lane];
            while (remainingWork > 0) {
                CoreUserState user = laneScan.riskUserId() == 0
                        ? nextRiskUser(state, positionUserIndex, scan.symbol(), lane, laneScan.lastUserId())
                        : state.user(laneScan.riskUserId());
                if (user == null) break;
                RiskLaneProgress previous = scan.laneProgress().isEmpty() ? null : scan.laneProgress().get(lane);
                if (laneScan.riskUserId() == 0 || remainingWork == allocations[lane]
                        && (previous == null || previous.userRevision() != user.revision()
                            || previous.marketRevision() != state.riskState().marketRevision()))
                    laneScan = laneScan.withRiskProgress(false, user.userId(), 0, "-", 0,
                            0, 0, 0, 0, laneScan.lastUserId());
                RiskUserPage page = processRiskUserPage(state, laneScan, user, instrument, mark, remainingWork,
                        snapshots, liquidations, nextLiquidationId, liquidationIndex);
                laneScan = page.scan();
                nextLiquidationId = page.nextLiquidationId();
                remainingWork -= Math.max(1, page.workUnits());
                if (page.userComplete()) laneScan = laneScan.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
            if (laneScan.riskUserId() == 0 && nextRiskUser(state, positionUserIndex, scan.symbol(), lane,
                    laneScan.lastUserId()) == null)
                laneScan = laneScan.withRiskProgress(true, 0, 0, "-", 0, 0, 0, 0, 0, laneScan.lastUserId());
            laneScans[lane] = laneScan;
        }
        var laneProgress = new java.util.ArrayList<RiskLaneProgress>(laneCount);
        boolean complete = true;
        for (int lane = 0; lane < laneCount; lane++) {
            var laneScan = laneScans[lane];
            var previous = scan.laneProgress().isEmpty() ? null : scan.laneProgress().get(lane);
            long userRevision = laneScan.riskUserId() == 0 ? 0 : allocations[lane] != 0
                    ? state.user(laneScan.riskUserId()).revision() : previous == null ? 0 : previous.userRevision();
            long marketRevision = allocations[lane] != 0 ? state.riskState().marketRevision()
                    : previous == null ? 0 : previous.marketRevision();
            laneProgress.add(riskLaneCursor(laneScan, laneScan.riskComplete(), userRevision, marketRevision));
            complete &= laneScan.riskComplete();
        }
        if (complete) nextLane = laneCount - 1;
        if (!complete) while (laneScans[nextLane].riskComplete()) nextLane = (nextLane + 1) % laneCount;
        CoreRiskState.RiskScan progress = laneScans[nextLane].withLaneProgress(laneProgress);
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(state.riskState().scans());
        CoreRiskState.RiskScan nextScan = progress.riskComplete()
                && scan.scanStartPriceSequence() != scan.priceSequence()
                ? new CoreRiskState.RiskScan(scan.symbol(), scan.priceSequence(), scan.priceSequence(), 0, false)
                        .withTriggerProgress(progress.triggerComplete(), progress.triggerPhase(),
                                progress.triggerPriceCursor(), progress.triggerOrderCursor(),
                                progress.triggerUpperId(), progress.triggerMarkPriceTicks(),
                                progress.triggerGeneratedAtEpochMillis())
                : progress;
        scans.put(scan.symbol(), nextScan.withLastScheduledRevision(Math.incrementExact(state.revision())));
        CoreRiskState nextRisk = new CoreRiskState(state.riskState().markPrices(), snapshots, liquidations,
                scans, nextLiquidationId, scanControl, state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), nextRisk, state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static RiskLaneProgress riskLaneCursor(CoreRiskState.RiskScan scan, boolean complete) {
        return riskLaneCursor(scan, complete, 0, 0);
    }

    private static RiskLaneProgress riskLaneCursor(CoreRiskState.RiskScan scan, boolean complete,
            long userRevision, long marketRevision) {
        return new RiskLaneProgress(scan.lastUserId(), complete, scan.riskUserId(), scan.riskPhase(),
                scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(),
                scan.riskUserId() == 0 ? 0 : userRevision, scan.riskUserId() == 0 ? 0 : marketRevision);
    }

    private static CoreRiskState.RiskScan riskLaneInput(CoreRiskState.RiskScan scan, int lane,
                                                         RiskLaneProgress cursor) {
        return new CoreRiskState.RiskScan(scan.symbol(), lane, scan.priceSequence(), scan.scanStartPriceSequence(),
                cursor.lastUserId(), cursor.complete(), cursor.userId(), cursor.phase(), cursor.positionCursor(),
                cursor.reservationCursor(), cursor.unrealizedPnlUnits(), cursor.maintenanceMarginUnits(),
                cursor.isolatedMarginUnits(), cursor.isolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision());
    }

    private long updateIsolatedRisk(TradingCoreState state, CoreUserState user, CorePositionState position,
                                    CoreInstrumentState instrument, CoreMarkPriceState mark,
                                    Map<String, CoreRiskSnapshot> snapshots,
                                    Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                    LiquidationIndex liquidationIndex) {
        PositionRisk risk = positionRisk(position, instrument, mark);
        long equity = Math.addExact(position.positionMarginUnits(), risk.equityDeltaUnits());
        long ratio = riskRatio(risk.maintenanceMarginUnits(), equity);
        CoreRiskStatus status = riskStatus(ratio);
        CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), position.symbol(), position.positionSide(),
                mark.priceSequence(), equity, risk.unrealizedPnlUnits(), risk.maintenanceMarginUnits(), ratio, status);
        snapshots.put(snapshot.key(), snapshot);
        return ensureLiquidation(user.userId(), position, instrument, mark.priceSequence(), status,
                liquidations, nextLiquidationId, liquidationIndex);
    }

    private RiskUserPage processRiskUserPage(TradingCoreState state, CoreRiskState.RiskScan scan,
                                             CoreUserState user, CoreInstrumentState changedInstrument,
                                             CoreMarkPriceState changedMark, int maxWork,
                                             Map<String, CoreRiskSnapshot> snapshots,
                                             Map<Long, CoreLiquidationState> liquidations,
                                             long nextLiquidationId, LiquidationIndex liquidationIndex) {
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
                Map.Entry<String, CorePositionState> entry = nextEntry(user.positions(), positionCursor);
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
                    if (position.symbol().equals(scan.symbol())) {
                        nextLiquidationId = updateIsolatedRisk(state, user, position, changedInstrument, changedMark,
                                snapshots, liquidations, nextLiquidationId, liquidationIndex);
                    }
                    continue;
                }
                if (!position.marginAsset().equals(changedInstrument.settleAsset())) continue;
                CoreInstrumentState positionInstrument = state.instruments().get(position.symbol());
                CoreMarkPriceState positionMark = state.riskState().markPrices().get(position.symbol());
                if (positionInstrument == null || positionMark == null) continue;
                PositionRisk risk = positionRisk(position, positionInstrument, positionMark);
                unrealized = Math.addExact(unrealized, risk.equityDeltaUnits());
                maintenance = Math.addExact(maintenance, risk.maintenanceMarginUnits());
                continue;
            }
            if (phase == 1) {
                Map.Entry<Long, OrderReservation> entry = nextEntry(user.reservations(), reservationCursor);
                if (entry == null) {
                    if (maintenance == 0) {
                        return new RiskUserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                nextLiquidationId, work, true);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = entry.getKey();
                OrderReservation reservation = entry.getValue();
                work++;
                CoreOrderState order = state.orders().get(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.asset().equals(changedInstrument.settleAsset())) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.remainingUnits());
                }
                continue;
            }
            Map.Entry<String, CorePositionState> entry = nextEntry(user.positions(), positionCursor);
            if (entry == null) {
                return new RiskUserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                        nextLiquidationId, work, true);
            }
            positionCursor = entry.getKey();
            CorePositionState position = entry.getValue();
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || !position.marginAsset().equals(changedInstrument.settleAsset())) continue;
            CoreInstrumentState positionInstrument = state.instruments().get(position.symbol());
            CoreMarkPriceState positionMark = state.riskState().markPrices().get(position.symbol());
            if (positionInstrument == null || positionMark == null) continue;
            PositionRisk risk = positionRisk(position, positionInstrument, positionMark);
            AssetBalance balance = user.balances().get(changedInstrument.settleAsset());
            long wallet = balance == null ? 0 : Math.subtractExact(
                    Math.subtractExact(balance.totalUnits(), isolatedMargin), isolatedReservation);
            if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            CoreRiskStatus status = riskStatus(ratio);
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), position.symbol(),
                    position.positionSide(), positionMark.priceSequence(), equity, risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), ratio, status);
            snapshots.put(snapshot.key(), snapshot);
            nextLiquidationId = ensureLiquidation(user.userId(), position, positionInstrument,
                    positionMark.priceSequence(), status, liquidations, nextLiquidationId, liquidationIndex);
        }
        return new RiskUserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), nextLiquidationId, work, false);
    }

    private PositionRisk positionRisk(CorePositionState position, CoreInstrumentState instrument,
                                      CoreMarkPriceState mark) {
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(),
                position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        return new PositionRisk(position, instrument, mark, unrealized, maintenance, equityDelta);
    }

    private long ensureLiquidation(long userId, CorePositionState position, CoreInstrumentState instrument,
                                   long priceSequence, CoreRiskStatus status,
                                   Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                   LiquidationIndex liquidationIndex) {
        long activeId = liquidationIndex == null ? 0
                : liquidationIndex.activeId(userId, position.symbol(), position.positionSide());
        CoreLiquidationState active = activeId == 0 ? null : liquidations.get(activeId);
        if (liquidationIndex == null) {
            active = liquidations.values().stream().filter(value -> value.userId() == userId
                    && value.symbol().equals(position.symbol()) && value.positionSide() == position.positionSide()
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED).findFirst().orElse(null);
        }
        if (status != CoreRiskStatus.LIQUIDATION
                || !CoreRiskPolicy.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                liquidations.put(active.liquidationId(), active.canceled());
            }
            return nextLiquidationId;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                liquidations.put(active.liquidationId(), active.refreshed(position.marginMode(), priceSequence,
                        position.signedQuantitySteps()));
            }
            return nextLiquidationId;
        }
        CoreLiquidationState liquidation = new CoreLiquidationState(nextLiquidationId, userId, position.symbol(),
                position.marginMode(), position.positionSide(), instrument.changeId(), priceSequence,
                position.signedQuantitySteps(), Math.absExact(position.signedQuantitySteps()), 0,
                0, 0, 0, CoreLiquidationState.Status.PLANNED);
        liquidations.put(nextLiquidationId, liquidation);
        return Math.incrementExact(nextLiquidationId);
    }

    private long riskRatio(long maintenance, long equity) {
        return maintenance <= 0 ? 0 : equity <= 0 ? Long.MAX_VALUE : safeRatio(maintenance, equity);
    }

    private static long safeRatio(long maintenance, long equity) {
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private CoreRiskStatus riskStatus(long ratio) {
        return CoreRiskPolicy.status(ratio);
    }

    @SuppressWarnings("unchecked")
    private CoreUserState nextRiskUser(TradingCoreState state, PositionUserIndex index,
                                       String symbol, int accountLaneId, long lastUserId) {
        if (index == null) {
            Map<Long, CoreUserState> users = state.users();
            if (users instanceof NavigableMap<?, ?> navigable) {
                Map.Entry<Long, CoreUserState> next =
                        ((NavigableMap<Long, CoreUserState>) navigable).higherEntry(lastUserId);
                while (next != null && topology.accountLaneId(next.getKey()) != accountLaneId) {
                    next = ((NavigableMap<Long, CoreUserState>) navigable).higherEntry(next.getKey());
                }
                return next == null ? null : next.getValue();
            }
            return users.values().stream().filter(user -> user.userId() > lastUserId)
                    .filter(user -> topology.accountLaneId(user.userId()) == accountLaneId)
                    .min(java.util.Comparator.comparingLong(CoreUserState::userId)).orElse(null);
        }
        Set<Long> indexedUsers = index.users(symbol);
        Long nextUserId;
        if (indexedUsers instanceof NavigableSet<?> navigable) {
            nextUserId = ((NavigableSet<Long>) navigable).higher(lastUserId);
            while (nextUserId != null && topology.accountLaneId(nextUserId) != accountLaneId) {
                nextUserId = ((NavigableSet<Long>) navigable).higher(nextUserId);
            }
        } else {
            nextUserId = indexedUsers.stream().filter(userId -> userId > lastUserId)
                    .filter(userId -> topology.accountLaneId(userId) == accountLaneId)
                    .min(Long::compareTo).orElse(null);
        }
        return nextUserId == null ? null : state.user(nextUserId);
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, CorePositionState> nextEntry(Map<String, CorePositionState> positions,
                                                                  String cursor) {
        NavigableMap<String, CorePositionState> sorted = positions instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<String, CorePositionState>) navigable : new java.util.TreeMap<>(positions);
        return "-".equals(cursor) ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<Long, OrderReservation> nextEntry(Map<Long, OrderReservation> reservations,
                                                               long cursor) {
        NavigableMap<Long, OrderReservation> sorted = reservations instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<Long, OrderReservation>) navigable : new java.util.TreeMap<>(reservations);
        return cursor == 0 ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    private record PositionRisk(CorePositionState position, CoreInstrumentState instrument,
                                CoreMarkPriceState mark, long unrealizedPnlUnits,
                                long maintenanceMarginUnits, long equityDeltaUnits) {}

    private record RiskUserPage(CoreRiskState.RiskScan scan, long nextLiquidationId,
                                int workUnits, boolean userComplete) {}
}
