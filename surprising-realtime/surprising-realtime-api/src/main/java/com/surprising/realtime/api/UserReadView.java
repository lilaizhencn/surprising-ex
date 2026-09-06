package com.surprising.realtime.api;

import com.surprising.aeron.protocol.*;

import java.util.*;

/** HTTP materialization lives outside Core. Entity updates contain absolute values. */
public record UserReadView(
        String status,
        String snapshotVersion,
        long snapshotAt,
        CoreUserStateView account,
        List<CoreOrderStateView> openOrders,
        List<CoreTriggerOrderStateView> triggerOrders,
        long exportSequence,
        List<PositionRisk> positionRisks) {
    public record PositionRisk(
            String symbol,
            CorePositionSide positionSide,
            String priceSequence,
            long equityUnits,
            long unrealizedPnlUnits,
            long maintenanceMarginUnits,
            long marginRatioPpm,
            String status) {
        public static PositionRisk decode(RealtimeFrame frame) {
            var b =
                    java.nio.ByteBuffer.wrap(frame.payload())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            long price = b.getLong(),
                    equity = b.getLong(),
                    pnl = b.getLong(),
                    maintenance = b.getLong(),
                    ratio = b.getLong();
            return new PositionRisk(
                    frame.symbol(),
                    CorePositionSide.values()[b.get()],
                    Long.toString(price),
                    equity,
                    pnl,
                    maintenance,
                    ratio,
                    switch (b.get()) {
                        case 0 -> "NORMAL";
                        case 1 -> "WARNING";
                        case 2 -> "LIQUIDATION";
                        default -> throw new IllegalArgumentException("invalid risk status");
                    });
        }
    }

    public static UserReadView from(ValkeyReadViewStore.ReadView source) {
        CoreUserStateView user = null;
        var risks = new TreeMap<String, PositionRisk>();
        var balances = new TreeMap<String, CoreBalanceView>();
        var positions = new TreeMap<String, CorePositionView>();
        var reservations = new TreeMap<Long, CoreReservationView>();
        var orders = new TreeMap<Long, CoreOrderStateView>();
        var triggers = new TreeMap<Long, CoreTriggerOrderStateView>();
        var leverages = new TreeMap<String, CoreLeverageView>();
        for (var f : source.frames()) {
            switch (f.kind()) {
                case USER -> {
                    user = CoreStateQueryCodec.decodeUserState(f.payload());
                    balances.clear();
                    positions.clear();
                    reservations.clear();
                    leverages.clear();
                    user.balances().forEach(v -> balances.put(v.asset(), v));
                    user.positions().stream()
                            .filter(v -> v.signedQuantitySteps() != 0)
                            .forEach(v -> positions.put(v.symbol() + ":" + v.positionSide(), v));
                    user.reservations().forEach(v -> reservations.put(v.orderId(), v));
                    user.leverages()
                            .forEach(v -> leverages.put(v.symbol() + ":" + v.marginMode(), v));
                }
                case METADATA -> {
                    var v = CoreStateQueryCodec.decodeUserState(f.payload());
                    if (user != null)
                        user =
                                new CoreUserStateView(
                                        user.productLine(),
                                        user.userId(),
                                        v.revision(),
                                        v.positionMode(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of());
                }
                case BALANCE ->
                        CoreStateQueryCodec.decodeUserState(f.payload())
                                .balances()
                                .forEach(v -> balances.put(v.asset(), v));
                case POSITION ->
                        CoreStateQueryCodec.decodeUserState(f.payload())
                                .positions()
                                .forEach(
                                        v -> {
                                            String key = v.symbol() + ":" + v.positionSide();
                                            if (v.signedQuantitySteps() == 0) positions.remove(key);
                                            else positions.put(key, v);
                                        });
                case RESERVATION ->
                        CoreStateQueryCodec.decodeUserState(f.payload())
                                .reservations()
                                .forEach(
                                        v -> {
                                            if (v.reservedUnits()
                                                            - v.releasedUnits()
                                                            - v.consumedUnits()
                                                    == 0) reservations.remove(v.orderId());
                                            else reservations.put(v.orderId(), v);
                                        });
                case LEVERAGE ->
                        CoreStateQueryCodec.decodeUserState(f.payload())
                                .leverages()
                                .forEach(v -> leverages.put(v.symbol() + ":" + v.marginMode(), v));
                case ORDER -> {
                    var v = CoreStateQueryCodec.decodeOrderState(f.payload());
                    if (v.status().equals("OPEN")) orders.put(v.orderId(), v);
                    else {
                        orders.remove(v.orderId());
                        reservations.remove(v.orderId());
                    }
                }
                case RISK -> risks.put(f.entityId(), PositionRisk.decode(f));
                case TRIGGER ->
                        CoreTriggerOrderCodec.decodeList(f.payload())
                                .forEach(
                                        v -> {
                                            if (v.status().open())
                                                triggers.put(v.triggerOrderId(), v);
                                            else triggers.remove(v.triggerOrderId());
                                        });
                default -> {}
            }
        }
        if (user != null)
            user =
                    new CoreUserStateView(
                            user.productLine(),
                            user.userId(),
                            user.revision(),
                            user.positionMode(),
                            List.copyOf(balances.values()),
                            List.copyOf(reservations.values()),
                            List.copyOf(positions.values()),
                            List.copyOf(leverages.values()));
        return new UserReadView(
                source.status(),
                source.snapshotVersion(),
                source.snapshotAt(),
                user,
                List.copyOf(orders.values()),
                List.copyOf(triggers.values()),
                source.exportSequence(),
                risks.values().stream()
                        .filter(r -> positions.containsKey(r.symbol() + ":" + r.positionSide()))
                        .toList());
    }
}
