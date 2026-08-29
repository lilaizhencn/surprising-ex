package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderType;

public final class PerpetualLaneJournal implements Runnable {
    final long coreSequence;
    final int laneId;
    final long beforeRevision;
    final long beforeStateHash;
    final long beforeFundsHash;
    final OrderRuntime[] beforeOrders;
    final ReservationRuntime[] beforeReservations;
    final PositionRuntime[] beforePositions;
    final long[] beforeAvailable;
    final long[] beforeLocked;
    final long[] positionKeys;
    final long[] accountUserIds;
    final int[] orderAccountIndexes;
    final int[] orderPositionIndexes;
    final long[] orderLeverages;
    final int[] operationOrderIndexes;
    final long[] operationPrices;
    final long[] operationQuantities;
    final boolean[] operationTakers;
    final CoreInstrumentState instrument;
    final int settleAssetId;
    final int takerOrderIndex;
    final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
    final long[] userRevisionIncrements;
    final OrderRuntime[] orders;
    final ReservationRuntime[] reservations;
    final PositionRuntime[] positions;
    final long[] available;
    final long[] locked;
    volatile boolean completed;
    volatile Throwable failure;

    PerpetualLaneJournal(long coreSequence, int laneId, AccountLaneView before,
                         OrderRuntime[] orders, ReservationRuntime[] reservations,
                         PositionRuntime[] positions, long[] positionKeys,
                         long[] accountUserIds, long[] available, long[] locked,
                         int[] orderAccountIndexes, int[] orderPositionIndexes,
                         long[] orderLeverages, int[] operationOrderIndexes,
                         long[] operationPrices, long[] operationQuantities,
                         boolean[] operationTakers, CoreInstrumentState instrument,
                         int settleAssetId, int takerOrderIndex) {
        this.coreSequence = coreSequence;
        this.laneId = laneId;
        this.beforeRevision = before.revision();
        this.beforeStateHash = before.localStateHash();
        this.beforeFundsHash = before.localFundsHash();
        this.beforeOrders = orders.clone();
        this.beforeReservations = reservations.clone();
        this.beforePositions = positions.clone();
        this.beforeAvailable = available.clone();
        this.beforeLocked = locked.clone();
        this.positionKeys = positionKeys;
        this.accountUserIds = accountUserIds;
        this.orderAccountIndexes = orderAccountIndexes;
        this.orderPositionIndexes = orderPositionIndexes;
        this.orderLeverages = orderLeverages;
        this.operationOrderIndexes = operationOrderIndexes;
        this.operationPrices = operationPrices;
        this.operationQuantities = operationQuantities;
        this.operationTakers = operationTakers;
        this.instrument = instrument;
        this.settleAssetId = settleAssetId;
        this.takerOrderIndex = takerOrderIndex;
        this.userRevisionIncrements = new long[accountUserIds.length];
        this.orders = orders;
        this.reservations = reservations;
        this.positions = positions;
        this.available = available;
        this.locked = locked;
    }

    @Override
    public void run() {
        try {
            for (int index = 0; index < operationOrderIndexes.length; index++) {
                int orderIndex = operationOrderIndexes[index];
                int accountIndex = orderAccountIndexes[orderIndex];
                int positionIndex = orderPositionIndexes[orderIndex];
                RuntimePerpetualFillCalculator.FillResult result = RuntimePerpetualFillCalculator.calculate(
                        instrument, orders[orderIndex], reservations[orderIndex], positions[positionIndex],
                        available[accountIndex], locked[accountIndex], operationPrices[index],
                        operationQuantities[index], operationTakers[index], orderLeverages[orderIndex],
                        settleAssetId);
                orders[orderIndex] = result.order();
                reservations[orderIndex] = result.reservation();
                positions[positionIndex] = result.position();
                available[accountIndex] = result.availableUnits();
                locked[accountIndex] = result.lockedUnits();
                treasuryDelta.addFee(settleAssetId, result.feeTreasuryUnits());
                treasuryDelta.addClearing(settleAssetId, result.clearingTreasuryUnits());
                userRevisionIncrements[accountIndex]++;
                if (!operationTakers[index] && orders[orderIndex].canceled()) releaseTerminal(orderIndex);
            }
            if (takerOrderIndex >= 0) {
                OrderRuntime taker = orders[takerOrderIndex];
                if (!taker.canceled() && (taker.timeInForce().immediate()
                        || taker.orderType() == CoreOrderType.MARKET)) {
                    orders[takerOrderIndex] = taker.withStatus(CoreOrderStatus.CANCELED,
                            Math.incrementExact(taker.revision()));
                }
                if (orders[takerOrderIndex].canceled()) releaseTerminal(takerOrderIndex);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            completed = true;
        }
    }

    private void releaseTerminal(int orderIndex) {
        ReservationRuntime reservation = reservations[orderIndex];
        long units = reservation.reservedUnits();
        if (units == 0) return;
        int accountIndex = orderAccountIndexes[orderIndex];
        available[accountIndex] = Math.addExact(available[accountIndex], units);
        locked[accountIndex] = Math.subtractExact(locked[accountIndex], units);
        if (locked[accountIndex] < 0) throw new IllegalStateException("terminal release exceeds locked balance");
        reservations[orderIndex] = reservation.release(units);
        userRevisionIncrements[accountIndex]++;
    }

    public boolean completed() { return completed; }
    public Throwable failure() { return failure; }
    public int laneId() { return laneId; }
}
