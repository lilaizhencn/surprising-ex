package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;
import java.util.UUID;

public record TradingRuntimeSnapshot(
        long revision,
        Map<Long, UserSnapshot> users,
        Map<BalanceKey, BalanceSnapshot> balances,
        Map<Long, OrderSnapshot> orders,
        Map<Long, ReservationSnapshot> reservations,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<PositionKey, PositionSnapshot> positions,
        Map<Long, LiquidationSnapshot> liquidations,
        Map<Integer, MarkPriceSnapshot> markPrices,
        Map<PositionKey, RiskSnapshot> riskSnapshots,
        Map<Integer, RiskScanSnapshot> riskScans,
        long nextLiquidationId,
        Map<Integer, TreasurySnapshot> treasury,
        Map<Integer, Long> fundingSettlements,
        Map<Integer, FundingProgressSnapshot> fundingProgress) {

    public TradingRuntimeSnapshot {
        if (revision < 0 || users == null || balances == null || orders == null
                || reservations == null || clientOrderIndex == null || positions == null || liquidations == null
                || markPrices == null || riskSnapshots == null || riskScans == null || nextLiquidationId <= 0
                || treasury == null
                || fundingSettlements == null || fundingProgress == null) {
            throw new IllegalArgumentException("invalid runtime snapshot");
        }
        users = immutableSorted(users);
        balances = immutableSorted(balances);
        orders = immutableSorted(orders);
        reservations = immutableSorted(reservations);
        clientOrderIndex = immutableSorted(clientOrderIndex);
        positions = immutableSorted(positions);
        liquidations = immutableSorted(liquidations);
        markPrices = immutableSorted(markPrices);
        riskSnapshots = immutableSorted(riskSnapshots);
        riskScans = immutableSorted(riskScans);
        treasury = immutableSorted(treasury);
        fundingSettlements = immutableSorted(fundingSettlements);
        fundingProgress = immutableSorted(fundingProgress);
    }

    public long totalAvailableUnits() {
        return balances.values().stream().mapToLong(BalanceSnapshot::availableUnits).sum();
    }

    public long totalLockedUnits() {
        return balances.values().stream().mapToLong(BalanceSnapshot::lockedUnits).sum();
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> immutableSorted(Map<K, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    public record UserSnapshot(long userId) {
        public UserSnapshot {
            if (userId <= 0) throw new IllegalArgumentException("invalid snapshot user");
        }
    }

    public record BalanceKey(long userId, int assetId) implements Comparable<BalanceKey> {
        public BalanceKey {
            if (userId <= 0 || assetId < 0) throw new IllegalArgumentException("invalid snapshot balance key");
        }

        @Override
        public int compareTo(BalanceKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Integer.compare(assetId, other.assetId);
        }
    }

    public record BalanceSnapshot(long availableUnits, long lockedUnits) {
        public BalanceSnapshot {
            if (availableUnits < 0 || lockedUnits < 0) {
                throw new IllegalArgumentException("invalid snapshot balance");
            }
        }
    }

    public record ClientOrderKey(long userId, long clientKey) implements Comparable<ClientOrderKey> {
        public ClientOrderKey {
            if (userId <= 0) throw new IllegalArgumentException("invalid snapshot client user");
        }

        @Override
        public int compareTo(ClientOrderKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Long.compare(clientKey, other.clientKey);
        }
    }

    public record OrderSnapshot(long userId, int symbolId, long instrumentVersion,
                                com.surprising.aeron.protocol.CoreOrderSide side, long priceTicks,
                                boolean reduceOnly, com.surprising.aeron.protocol.CoreMarginMode marginMode,
                                com.surprising.aeron.protocol.CorePositionSide positionSide,
                                com.surprising.aeron.protocol.CoreOrderType orderType,
                                com.surprising.aeron.protocol.CoreTimeInForce timeInForce,
                                long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                                long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
        public OrderSnapshot(long userId, int symbolId, long quantitySteps, boolean canceled) {
            this(userId, symbolId, 1, com.surprising.aeron.protocol.CoreOrderSide.BUY, 0, false,
                    com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                    com.surprising.aeron.protocol.CorePositionSide.NET,
                    com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                    com.surprising.aeron.protocol.CoreTimeInForce.GTC, 0, 0,
                    quantitySteps, 0, quantitySteps, canceled);
        }

        public OrderSnapshot(long userId, int symbolId, long quantitySteps) {
            this(userId, symbolId, quantitySteps, false);
        }

        public OrderSnapshot {
            if (userId <= 0 || symbolId < 0 || instrumentVersion <= 0 || side == null || priceTicks < 0
                    || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                    || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                    || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps) {
                throw new IllegalArgumentException("invalid snapshot order");
            }
        }
    }

    public record ReservationSnapshot(long userId, int assetId, long reservedUnits) {
        public ReservationSnapshot {
            if (userId <= 0 || assetId < 0 || reservedUnits < 0) {
                throw new IllegalArgumentException("invalid snapshot reservation");
            }
        }
    }

    public record PositionKey(long userId, long positionKey) implements Comparable<PositionKey> {
        public PositionKey {
            if (userId <= 0 || positionKey <= 0) throw new IllegalArgumentException("invalid snapshot position key");
        }

        @Override
        public int compareTo(PositionKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Long.compare(positionKey, other.positionKey);
        }
    }

    public record PositionSnapshot(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                   CorePositionSide positionSide, long instrumentVersion,
                                   long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                   long realizedPnlUnits, long positionMarginUnits) {
        public PositionSnapshot {
            if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                    || positionMarginUnits < 0) {
                throw new IllegalArgumentException("invalid snapshot position");
            }
        }
    }

    public record LiquidationSnapshot(long userId, int symbolId, CoreMarginMode marginMode,
                                      CorePositionSide positionSide, long instrumentVersion,
                                      long triggerPriceSequence, long signedQuantitySteps,
                                      long closeQuantitySteps, long deficitUnits, long executionPriceTicks,
                                      long liquidationFeeRatePpm, long liquidationFeeUnits,
                                      CoreLiquidationState.Status status, long nextCancelOrderId) {
        public LiquidationSnapshot {
            if (userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                    || instrumentVersion <= 0 || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                    || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                    || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                    || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                    || nextCancelOrderId < 0) {
                throw new IllegalArgumentException("invalid snapshot liquidation");
            }
        }
    }

    public record MarkPriceSnapshot(long instrumentVersion, long markPriceTicks, long priceSequence) {
        public MarkPriceSnapshot {
            if (instrumentVersion <= 0 || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid snapshot mark price");
            }
        }
    }

    public record RiskSnapshot(long userId, int symbolId, CorePositionSide positionSide,
                               long priceSequence, long equityUnits, long unrealizedPnlUnits,
                               long maintenanceMarginUnits, long marginRatioPpm, CoreRiskStatus status) {
        public RiskSnapshot {
            if (userId <= 0 || symbolId < 0 || positionSide == null || priceSequence <= 0
                    || maintenanceMarginUnits < 0 || marginRatioPpm < 0 || status == null) {
                throw new IllegalArgumentException("invalid snapshot risk");
            }
        }
    }

    public record RiskScanSnapshot(long priceSequence, long scanStartPriceSequence, long lastUserId,
                                   boolean riskComplete, long riskUserId, int riskPhase,
                                   String riskPositionCursor, long riskReservationCursor,
                                   long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                                   long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                                   boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                                   long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                                   long triggerGeneratedAtEpochMillis, long triggerOcoOrderId,
                                   long triggerOcoCursor) {
    }

    public record TreasurySnapshot(long feeUnits, long insuranceUnits, long insuranceDeficitUnits) {
        public TreasurySnapshot {
            if (insuranceUnits < 0 || insuranceDeficitUnits < 0
                    || insuranceUnits != 0 && insuranceDeficitUnits != 0) {
                throw new IllegalArgumentException("invalid snapshot treasury");
            }
        }
    }

    public record FundingProgressSnapshot(long settlementId, long instrumentVersion, long fundingRatePpm,
                                          long nextCursorUserId, UUID commandId) {
        public FundingProgressSnapshot {
            if (settlementId <= 0 || instrumentVersion <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || commandId == null) {
                throw new IllegalArgumentException("invalid snapshot funding progress");
            }
        }
    }
}
