package com.surprising.aeron.service.position;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;

public final class Position {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    /** 保证金或结算资产的非负整数编号，金额字段都要按这项资产的单位理解。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreMarginMode marginMode;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CorePositionSide positionSide;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long signedQuantitySteps;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long entryPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long entryValueTicks;
    /** 已经实现的累计盈亏，正数盈利、负数亏损，单位为结算资产最小单位。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long realizedPnlUnits;
    /** 分配给持仓的非负保证金金额，单位为保证金资产最小单位，空仓时为 0。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long positionMarginUnits;
    private boolean mutable;
    private boolean publicationSlot;

    private Position() { publicationSlot = true; }

    /** 校验并创建账户 Lane 的持仓对象，初始化全部身份和金额字段，并标记为可在 Lane 路径复用。 */
    public Position(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                           CorePositionSide positionSide, CoreInstrument instrument,
                           long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                           long realizedPnlUnits, long positionMarginUnits) {
        validate(userId, symbolId, assetId, marginMode, positionSide, instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.userId = userId; this.symbolId = symbolId; this.assetId = assetId;
        this.marginMode = marginMode; this.positionSide = positionSide;
        this.instrument = instrument; this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks; this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits; this.positionMarginUnits = positionMarginUnits;
        this.mutable = true;
    }

    /** 空仓不能残留入场价、入场价格量或保证金，有仓则必须具备正的入场信息。 */
    private static void validate(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                 long positionMarginUnits) {
        if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                || instrument == null
                || positionMarginUnits < 0) throw new IllegalArgumentException("invalid runtime position");
        if (signedQuantitySteps == 0) {
            if (entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0)
                throw new IllegalArgumentException("flat runtime position contains open state");
        } else if (entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open runtime position is incomplete");
        }
    }

    /** 复制当前持仓的身份和金额字段，并把副本标记为发布值。 */
    public Position snapshot() {
        Position copy = new Position(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        copy.mutable = false;
        return copy;
    }

    /** 把全部身份和业务字段复制到指定发布槽，目标为空时创建槽位。普通持仓对象不能作为槽位接收方，返回引用只在缓冲区约定的使用期内有效。 */
    public Position copyIntoPublicationSlot(Position target) {
        if (target == null) target = new Position();
        if (!target.publicationSlot) throw new IllegalArgumentException("position target is not a publication slot");
        target.userId = userId;
        target.symbolId = symbolId;
        target.assetId = assetId;
        target.marginMode = marginMode;
        target.positionSide = positionSide;
        target.instrument = instrument;
        target.signedQuantitySteps = signedQuantitySteps;
        target.entryPriceTicks = entryPriceTicks;
        target.entryValueTicks = entryValueTicks;
        target.realizedPnlUnits = realizedPnlUnits;
        target.positionMarginUnits = positionMarginUnits;
        return target;
    }

    /** 仅清理发布槽持有的配置和枚举引用，供槽位复用或释放引用。标量数值保留，不能再把清理后的槽当作完整持仓读取。 */
    public void clearPublicationSlot() {
        if (!publicationSlot) throw new IllegalStateException("position is not a publication slot");
        instrument = null;
        marginMode = null;
        positionSide = null;
    }

    /** 已经是 Lane 值时直接返回自身，否则用当前字段创建新的 Lane 持仓，避免把发布对象直接当作账户权威状态。 */
    public Position laneValue() {
        return mutable ? this : new Position(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits,
                positionMarginUnits);
    }

    /** Lane 值需要先生成发布副本。 */
    public Position publicationValue() { return mutable ? snapshot() : this; }

    /** 在原对象上更新数量、入场信息、盈亏及保证金。要求配置指向同一合约并通过持仓校验，调用方负责遵守线程所有权和发布引用使用期。 */
    public void applyInPlace(CoreInstrument instrument, long signedQuantitySteps, long entryPriceTicks,
                      long entryValueTicks, long realizedPnlUnits, long positionMarginUnits) {
        if (this.instrument != instrument && (instrument == null || !this.instrument.instrumentId().equals(instrument.instrumentId()))) {
            throw new IllegalArgumentException("position instrument mismatch");
        }
        validate(userId, symbolId, assetId, marginMode, positionSide, this.instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks;
        this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits;
        this.positionMarginUnits = positionMarginUnits;
    }

    /** 按持仓身份及业务数值比较。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Position value)) return false;
        return userId == value.userId && symbolId == value.symbolId && assetId == value.assetId
                && (instrument == value.instrument || (instrument != null && value.instrument != null && instrument.instrumentId().equals(value.instrument.instrumentId())))
                && signedQuantitySteps == value.signedQuantitySteps
                && entryPriceTicks == value.entryPriceTicks && entryValueTicks == value.entryValueTicks
                && realizedPnlUnits == value.realizedPnlUnits && positionMarginUnits == value.positionMarginUnits
                && marginMode == value.marginMode && positionSide == value.positionSide;
    }

    /** 用持仓身份、金额字段及配置对象身份计算散列值，不把 Lane 或发布槽标记计入。 */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(userId, symbolId, assetId, marginMode, positionSide,
                System.identityHashCode(instrument), signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
    }

    /** 生成包含用户、币对、资产、持仓腿和带方向数量的简短文本，便于定位运行时持仓。 */
    @Override
    public String toString() {
        return "Position[userId=" + userId + ", symbolId=" + symbolId + ", assetId=" + assetId
                + ", positionSide=" + positionSide + ", signedQuantitySteps=" + signedQuantitySteps + "]";
    }

    public static final class CloseCapacity {
        private static final Comparator<Order> NEWEST_FIRST =
                Comparator.comparingLong(Order::createdAtEpochMillis).reversed()
                        .thenComparing(Comparator.comparingLong(Order::orderId).reversed());

        private CloseCapacity() {
        }

        /** 普通平仓单和只减仓单都占用容量，只有已提交的只减仓挂单可以作为撤单候选。 */
        public static List<Long> conflictsFor(AccountPartition lane, long userId, int symbolId,
                long positionKey, CorePositionSide positionSide, CoreOrderSide closeSide,
                long excludedOrderId, long incomingQuantitySteps) {
            if (incomingQuantitySteps <= 0) {
                throw new IllegalArgumentException("incoming close quantity must be positive");
            }
            Position position = lane.position(positionKey);
            long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
            long requestedQuantity = 0;
            var ids = lane.activeOrderIds(userId);
            if (ids == null) return List.of();
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                long orderId = iterator.nextValue();
                if (orderId == excludedOrderId || lane.pendingReservation(orderId)) continue;
                Order order = lane.order(orderId);
                if (matches(order, userId, symbolId, positionSide, closeSide)) {
                    if (order.reduceOnly() && (orderId <= 0 || order.remainingQuantitySteps() <= 0
                            || order.clusterPosition() < 0)) {
                        throw new IllegalArgumentException("invalid close-capacity commitment");
                    }
                    requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
                }
            }
            long overflow = Math.subtractExact(Math.addExact(requestedQuantity, incomingQuantitySteps), positionQuantity);
            if (overflow <= 0) return List.of();

            ArrayList<Order> candidates = new ArrayList<>();
            iterator = ids.iterator();
            while (iterator.hasNext()) {
                long orderId = iterator.nextValue();
                if (orderId == excludedOrderId || lane.pendingReservation(orderId)) continue;
                Order order = lane.order(orderId);
                if (matches(order, userId, symbolId, positionSide, closeSide) && order.reduceOnly()) {
                    candidates.add(order);
                }
            }
            if (candidates.isEmpty()) return List.of();
            candidates.sort(NEWEST_FIRST);
            long[] conflicts = new long[candidates.size()];
            int count = 0;
            long released = 0;
            for (int index = 0; index < candidates.size(); index++) {
                Order order = candidates.get(index);
                conflicts[count++] = order.orderId();
                released = Math.addExact(released, order.remainingQuantitySteps());
                if (released >= overflow) break;
            }
            return ImmutableLongArrayList.takeOwnership(count == conflicts.length
                    ? conflicts : Arrays.copyOf(conflicts, count));
        }

        private static boolean matches(Order order, long userId, int symbolId,
                CorePositionSide positionSide, CoreOrderSide closeSide) {
            return order != null && order.status() == CoreOrderStatus.OPEN && order.userId() == userId
                    && order.symbolId() == symbolId && order.positionSide() == positionSide && order.side() == closeSide;
        }
    }

    public record IndexValue(long userId, String instrumentId, String asset,
                                 CorePositionSide positionSide, long signedQuantitySteps) {

}

}
