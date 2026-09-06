package com.surprising.aeron.service.state;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Owner-only boundary encoder. Mutable runtime objects never escape this class. */
public final class RealtimeStateCapture {
    private final RealtimeOutbox outbox;
    private final ProductLine product;
    private final RuntimeIdentityRegistry identities;
    private boolean tradesOnly;

    public void tradesOnly(boolean value) {
        tradesOnly = value;
    }

    public boolean privateStateEnabled() {
        return !tradesOnly && active();
    }

    private long sequence, timestamp, snapshotId;
    private int ordinal;
    private long failures;
    private long exportSequence;
    private long previousExportSequence;

    public RealtimeStateCapture(
            RealtimeOutbox outbox, ProductLine product, RuntimeIdentityRegistry identities) {
        this.outbox = outbox;
        this.product = product;
        this.identities = identities;
    }

    public void begin(long position, long time, long snapshot) {
        begin(position, time, snapshot, 0);
    }

    public void begin(long position, long time, long snapshot, long previousExportSequence) {
        this.previousExportSequence = previousExportSequence;
        this.exportSequence = previousExportSequence;
        sequence = position;
        timestamp = time;
        snapshotId = snapshot;
        ordinal = 0;
        outbox.begin();
        if (snapshot == 0)
            emit(
                    RealtimeFrame.Kind.COMMIT_BEGIN,
                    0,
                    "",
                    "",
                    ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(previousExportSequence)
                            .array());
    }

    public boolean active() {
        return outbox.active();
    }

    public void commit(long exportSequence) {
        this.exportSequence = exportSequence;
        commit();
    }

    public void commit() {
        if (snapshotId == 0 && active()) {
            if (ordinal == 1 && exportSequence == previousExportSequence) {
                abort();
                return;
            }
            emit(
                    RealtimeFrame.Kind.COMMIT_END,
                    0,
                    "",
                    "",
                    ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(exportSequence)
                            .array());
        }
        outbox.commit();
    }

    public void abort() {
        outbox.abort();
    }

    public long failures() {
        return failures;
    }

    public void failed() {
        failures++;
        abort();
    }

    public void emit(
            RealtimeFrame.Kind kind, long userId, String symbol, String key, byte[] payload) {
        if (!active()) return;
        outbox.stage(
                RealtimeFrameCodec.encode(
                        new RealtimeFrame(
                                product,
                                kind,
                                userId,
                                sequence,
                                ordinal++,
                                timestamp,
                                snapshotId,
                                symbol,
                                key,
                                payload)));
    }

    public void snapshot(long userId, RealtimeUserSnapshot snapshot, long exportSequence) {
        var balances =
                snapshot.balances().stream()
                        .map(
                                v ->
                                        new CoreBalanceView(
                                                identities.asset(v.assetId()),
                                                v.available(),
                                                v.locked()))
                        .toList();
        var reservations =
                snapshot.reservations().stream()
                        .map(
                                v ->
                                        new CoreReservationView(
                                                v.orderId(),
                                                identities.symbol(v.symbolId()),
                                                v.instrumentChangeId(),
                                                v.kind(),
                                                identities.asset(v.assetId()),
                                                v.totalReservedUnits(),
                                                v.releasedUnits(),
                                                v.consumedUnits(),
                                                v.orderQuantitySteps()))
                        .toList();
        var positions =
                snapshot.positions().stream()
                        .map(
                                v ->
                                        new CorePositionView(
                                                identities.symbol(v.symbolId()),
                                                identities.asset(v.assetId()),
                                                v.marginMode(),
                                                v.positionSide(),
                                                v.instrumentChangeId(),
                                                v.signedQuantitySteps(),
                                                v.entryPriceTicks(),
                                                v.entryValueTicks(),
                                                v.realizedPnlUnits(),
                                                v.positionMarginUnits()))
                        .toList();
        var leverages =
                snapshot.leverages().stream()
                        .map(
                                v ->
                                        new CoreLeverageView(
                                                v.key().symbol(), v.key().marginMode(), v.value()))
                        .toList();
        var u = snapshot.user();
        emit(RealtimeFrame.Kind.SNAPSHOT_BEGIN, userId, "", "", new byte[0]);
        user(
                new CoreUserStateView(
                        product,
                        userId,
                        u == null ? 0 : u.revision(),
                        u == null ? CorePositionMode.ONE_WAY : u.positionMode(),
                        balances,
                        reservations,
                        positions,
                        leverages));
        snapshot.orders().forEach(this::order);
        snapshot.triggers().forEach(this::trigger);
        snapshot.risks().forEach(this::risk);
        emit(
                RealtimeFrame.Kind.SNAPSHOT_END,
                userId,
                "",
                "",
                ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(exportSequence)
                        .array());
    }

    public void user(CoreUserStateView view) {
        emit(
                RealtimeFrame.Kind.USER,
                view.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserState(view));
    }

    public void balance(long userId, int assetId, long available, long locked) {
        if (tradesOnly) return;
        if (!active()) return;
        try {
            var view = new CoreBalanceView(identities.asset(assetId), available, locked);
            emit(
                    RealtimeFrame.Kind.BALANCE,
                    userId,
                    "",
                    view.asset(),
                    CoreStateQueryCodec.encodeUserState(
                            new CoreUserStateView(
                                    product, userId, 0, List.of(view), List.of(), List.of())));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    public void metadata(UserRuntime u) {
        if (tradesOnly) return;
        if (!active() || u == null) return;
        emit(
                RealtimeFrame.Kind.METADATA,
                u.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserState(
                        new CoreUserStateView(
                                product,
                                u.userId(),
                                u.revision(),
                                u.positionMode(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of())));
    }

    public void reservation(ReservationRuntime r) {
        if (tradesOnly) return;
        if (!active() || r == null) return;
        var v =
                new CoreReservationView(
                        r.orderId(),
                        identities.symbol(r.symbolId()),
                        r.instrumentChangeId(),
                        r.kind(),
                        identities.asset(r.assetId()),
                        r.totalReservedUnits(),
                        r.releasedUnits(),
                        r.consumedUnits(),
                        r.orderQuantitySteps());
        emit(
                RealtimeFrame.Kind.RESERVATION,
                r.userId(),
                v.symbol(),
                Long.toString(r.orderId()),
                CoreStateQueryCodec.encodeUserState(
                        new CoreUserStateView(
                                product, r.userId(), 0, List.of(), List.of(v), List.of())));
    }

    public void leverage(CoreLeverageKey key, long value) {
        if (tradesOnly) return;
        if (!active()) return;
        try {
            var v = new CoreLeverageView(key.symbol(), key.marginMode(), value);
            emit(
                    RealtimeFrame.Kind.LEVERAGE,
                    key.userId(),
                    key.symbol(),
                    key.symbol() + ":" + key.marginMode(),
                    CoreStateQueryCodec.encodeUserState(
                            new CoreUserStateView(
                                    product,
                                    key.userId(),
                                    0,
                                    CorePositionMode.ONE_WAY,
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(v))));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    public void removedPosition(PositionRuntime p) {
        if (tradesOnly) return;
        if (!active() || p == null) return;
        position(
                new PositionRuntime(
                        p.userId(),
                        p.symbolId(),
                        p.assetId(),
                        p.marginMode(),
                        p.positionSide(),
                        0,
                        0,
                        0,
                        0,
                        p.realizedPnlUnits(),
                        0));
    }

    public void position(PositionRuntime p) {
        if (tradesOnly) return;
        if (!active() || p == null) return;
        var view =
                new CorePositionView(
                        identities.symbol(p.symbolId()),
                        identities.asset(p.assetId()),
                        p.marginMode(),
                        p.positionSide(),
                        p.instrumentChangeId(),
                        p.signedQuantitySteps(),
                        p.entryPriceTicks(),
                        p.entryValueTicks(),
                        p.realizedPnlUnits(),
                        p.positionMarginUnits());
        emit(
                RealtimeFrame.Kind.POSITION,
                p.userId(),
                view.symbol(),
                view.symbol() + ":" + view.positionSide(),
                CoreStateQueryCodec.encodeUserState(
                        new CoreUserStateView(
                                product, p.userId(), 0, List.of(), List.of(), List.of(view))));
    }

    public void order(OrderRuntime o) {
        if (tradesOnly) return;
        if (!active() || o == null) return;
        try {
            CoreOrderStateView v =
                    new CoreOrderStateView(
                            o.orderId(),
                            o.productLine(),
                            o.userId(),
                            identities.symbol(o.symbolId()),
                            o.instrumentChangeId(),
                            o.side(),
                            o.priceTicks(),
                            o.quantitySteps(),
                            o.executedQuantitySteps(),
                            o.remainingQuantitySteps(),
                            o.reduceOnly(),
                            o.marginMode(),
                            o.positionSide(),
                            o.orderType(),
                            o.timeInForce(),
                            o.postOnly(),
                            o.clientOrderId(),
                            o.commandId(),
                            o.makerFeeRatePpm(),
                            o.takerFeeRatePpm(),
                            o.cumulativeFeeUnits(),
                            o.createdAtEpochMillis(),
                            o.updatedAtEpochMillis(),
                            o.clusterPosition(),
                            o.status().name(),
                            o.revision());
            order(v);
        } catch (RuntimeException failure) {
            failed();
        }
    }

    public void order(CoreOrderStateView o) {
        emit(
                RealtimeFrame.Kind.ORDER,
                o.userId(),
                o.symbol(),
                Long.toString(o.orderId()),
                CoreStateQueryCodec.encodeOrderState(o));
    }

    public void trigger(CoreTriggerOrderState t) {
        if (tradesOnly) return;
        if (!active() || t == null) return;
        emit(
                RealtimeFrame.Kind.TRIGGER,
                t.userId(),
                t.symbol(),
                Long.toString(t.triggerOrderId()),
                CoreTriggerOrderCodec.encodeList(List.of(t.view())));
    }

    public void trade(
            OrderRuntime taker,
            long matcherSequence,
            int fillIndex,
            long price,
            long quantity,
            long makerOrderId,
            long makerUserId) {
        if (!active() || taker == null) return;
        byte[] data =
                ByteBuffer.allocate(33)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(taker.instrumentChangeId())
                        .putLong(price)
                        .putLong(quantity)
                        .putLong(matcherSequence)
                        .put((byte) taker.side().ordinal())
                        .array();
        emit(
                RealtimeFrame.Kind.TRADE,
                0,
                identities.symbol(taker.symbolId()),
                product
                        + ":"
                        + sequence
                        + ":"
                        + matcherSequence
                        + ":"
                        + taker.orderId()
                        + ":"
                        + fillIndex,
                data);
        if (tradesOnly) return;
        String id =
                product
                        + ":"
                        + sequence
                        + ":"
                        + matcherSequence
                        + ":"
                        + taker.orderId()
                        + ":"
                        + fillIndex;
        execution(
                taker.userId(),
                taker.orderId(),
                taker.instrumentChangeId(),
                identities.symbol(taker.symbolId()),
                id,
                price,
                quantity,
                taker.side(),
                false);
        execution(
                makerUserId,
                makerOrderId,
                taker.instrumentChangeId(),
                identities.symbol(taker.symbolId()),
                id,
                price,
                quantity,
                taker.side() == CoreOrderSide.BUY ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                true);
    }

    private void execution(
            long user,
            long order,
            long instrument,
            String symbol,
            String id,
            long price,
            long quantity,
            CoreOrderSide side,
            boolean maker) {
        byte[] payload =
                ByteBuffer.allocate(34)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(instrument)
                        .putLong(order)
                        .putLong(price)
                        .putLong(quantity)
                        .put((byte) side.ordinal())
                        .put((byte) (maker ? 1 : 0))
                        .array();
        emit(RealtimeFrame.Kind.EXECUTION, user, symbol, id, payload);
    }

    public void risk(RiskSnapshotRuntime risk) {
        if (tradesOnly) return;
        if (!active() || risk == null) return;
        byte[] payload =
                ByteBuffer.allocate(42)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(risk.priceSequence())
                        .putLong(risk.equityUnits())
                        .putLong(risk.unrealizedPnlUnits())
                        .putLong(risk.maintenanceMarginUnits())
                        .putLong(risk.marginRatioPpm())
                        .put((byte) risk.positionSide().ordinal())
                        .put((byte) risk.status().ordinal())
                        .array();
        String symbol = identities.symbol(risk.symbolId());
        emit(
                RealtimeFrame.Kind.RISK,
                risk.userId(),
                symbol,
                symbol + ":" + risk.positionSide(),
                payload);
    }
}
