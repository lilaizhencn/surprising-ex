package com.surprising.aeron.service.state.realtime;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** Owner-only boundary encoder. Mutable runtime objects never escape this class. */
public final class RealtimeStateCapture {
    private final RealtimeOutbox outbox;
    private final ProductLine product;
    private final RuntimeIdentityRegistry identities;
    private boolean orderProjectionOnly;

    public void orderProjectionOnly(boolean value) {
        orderProjectionOnly = value;
    }

    public boolean privateStateEnabled() {
        return active();
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
        if (snapshot == 0) {
            outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                    product, RealtimeFrame.Kind.COMMIT_BEGIN, 0, sequence, ordinal++, timestamp, snapshotId, "", "", previousExportSequence));
        }
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
            outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                    product, RealtimeFrame.Kind.COMMIT_END, 0, sequence, ordinal++, timestamp, snapshotId, "", "", exportSequence));
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
            RealtimeFrame.Kind kind, long userId, String instrumentId, String key, byte[] payload) {
        if (!active()) return;
        outbox.stage(
                RealtimeFrameCodec.encode(
                                product,
                                kind,
                                userId,
                                sequence,
                                ordinal++,
                                timestamp,
                                snapshotId,
                                instrumentId,
                                key,
                                payload));
    }

    public void snapshot(long userId, RealtimeUserSnapshot snapshot, long exportSequence) {
        var rawBalances = snapshot.balances();
        var balances = new java.util.ArrayList<CoreBalanceView>(rawBalances.size());
        for (int i = 0; i < rawBalances.size(); i++) {
            var v = rawBalances.get(i);
            balances.add(new CoreBalanceView(
                    identities.asset(v.assetId()),
                    v.available(),
                    v.locked()));
        }

        var rawReservations = snapshot.reservations();
        var reservations = new java.util.ArrayList<CoreReservationView>(rawReservations.size());
        for (int i = 0; i < rawReservations.size(); i++) {
            var v = rawReservations.get(i);
            reservations.add(new CoreReservationView(
                    v.orderId(),
                    identities.instrumentId(v.symbolId()),
                    v.kind(),
                    identities.asset(v.assetId()),
                    v.totalReservedUnits(),
                    v.releasedUnits(),
                    v.consumedUnits(),
                    v.orderQuantitySteps()));
        }

        var rawPositions = snapshot.positions();
        var positions = new java.util.ArrayList<CorePositionView>(rawPositions.size());
        for (int i = 0; i < rawPositions.size(); i++) {
            var v = rawPositions.get(i);
            positions.add(new CorePositionView(
                    identities.instrumentId(v.symbolId()),
                    identities.asset(v.assetId()),
                    v.marginMode(),
                    v.positionSide(),
                    v.signedQuantitySteps(),
                    v.entryPriceTicks(),
                    v.entryValueTicks(),
                    v.realizedPnlUnits(),
                    v.positionMarginUnits()));
        }

        var rawLeverages = snapshot.leverages();
        var leverages = new java.util.ArrayList<CoreLeverageView>(rawLeverages.size());
        for (int i = 0; i < rawLeverages.size(); i++) {
            var v = rawLeverages.get(i);
            leverages.add(new CoreLeverageView(
                    v.key().instrumentId(), v.key().marginMode(), v.value()));
        }
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
        outbox.stage(RealtimeFrameCodec.encodeLongPayload(
                product, RealtimeFrame.Kind.SNAPSHOT_END, userId, sequence, ordinal++, timestamp, snapshotId, "", "", exportSequence));
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
        if (orderProjectionOnly) return;
        if (!active()) return;
        try {
            String assetName = identities.asset(assetId);
            emit(
                    RealtimeFrame.Kind.BALANCE,
                    userId,
                    "",
                    assetName,
                    CoreStateQueryCodec.encodeSingleBalanceUserState(product, userId, assetName, available, locked));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    public void metadata(UserRuntime u) {
        if (orderProjectionOnly) return;
        if (!active() || u == null) return;
        emit(
                RealtimeFrame.Kind.METADATA,
                u.userId(),
                "",
                "user",
                CoreStateQueryCodec.encodeUserMetadataState(product, u.userId(), u.revision(), u.positionMode()));
    }

    public void reservation(ReservationRuntime r) {
        if (orderProjectionOnly) return;
        if (!active() || r == null) return;
        String instrumentId = identities.instrumentId(r.symbolId());
        String asset = identities.asset(r.assetId());
        emit(
                RealtimeFrame.Kind.RESERVATION,
                r.userId(),
                instrumentId,
                Long.toString(r.orderId()),
                CoreStateQueryCodec.encodeSingleReservationUserState(
                        product, r.userId(), r.orderId(), instrumentId, r.kind(), asset,
                        r.totalReservedUnits(), r.releasedUnits(), r.consumedUnits(), r.orderQuantitySteps()));
    }

    public void leverage(CoreLeverageKey key, long value) {
        if (orderProjectionOnly) return;
        if (!active()) return;
        try {
            emit(
                    RealtimeFrame.Kind.LEVERAGE,
                    key.userId(),
                    key.instrumentId(),
                    key.instrumentId() + ":" + key.marginMode(),
                    CoreStateQueryCodec.encodeSingleLeverageUserState(
                            product, key.userId(), key.instrumentId(), key.marginMode(), value));
        } catch (RuntimeException failure) {
            failed();
        }
    }

    public void removedPosition(PositionRuntime p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        String instrumentId = identities.instrumentId(p.symbolId());
        String asset = identities.asset(p.assetId());
        emit(
                RealtimeFrame.Kind.POSITION,
                p.userId(),
                instrumentId,
                instrumentId + ":" + p.positionSide(),
                CoreStateQueryCodec.encodeSinglePositionUserState(
                        product, p.userId(), instrumentId, asset, p.marginMode(), p.positionSide(),
                        0, 0, 0, p.realizedPnlUnits(), 0));
    }

    public void position(PositionRuntime p) {
        if (orderProjectionOnly) return;
        if (!active() || p == null) return;
        String instrumentId = identities.instrumentId(p.symbolId());
        String asset = identities.asset(p.assetId());
        emit(
                RealtimeFrame.Kind.POSITION,
                p.userId(),
                instrumentId,
                instrumentId + ":" + p.positionSide(),
                CoreStateQueryCodec.encodeSinglePositionUserState(
                        product, p.userId(), instrumentId, asset, p.marginMode(), p.positionSide(),
                        p.signedQuantitySteps(), p.entryPriceTicks(), p.entryValueTicks(),
                        p.realizedPnlUnits(), p.positionMarginUnits()));
    }

    public void order(OrderRuntime o) {
        if (!active() || o == null) return;
        try {
            CoreOrderStateView v =
                    new CoreOrderStateView(
                            o.orderId(),
                            o.productLine(),
                            o.userId(),
                            identities.instrumentId(o.symbolId()),
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
                            o.cumulativeFeeUnits(), o.executedValueHigh(), o.executedValueLow(),
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
        if (!active()) return;
        outbox.stage(RealtimeFrameCodec.encodeOrder(o, sequence, ordinal++, timestamp, snapshotId));
    }

    public void trigger(CoreTriggerOrderState t) {
        if (!active() || t == null) return;
        emit(
                RealtimeFrame.Kind.TRIGGER,
                t.userId(),
                t.instrumentId(),
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
        trade(taker.userId(), taker.orderId(), taker.symbolId(), taker.side(), matcherSequence,
                fillIndex, price, quantity, makerOrderId, makerUserId);
    }

    public void trade(
            long takerUserId,
            long takerOrderId,
            int takerSymbolId,
            CoreOrderSide takerSide,
            long matcherSequence,
            int fillIndex,
            long price,
            long quantity,
            long makerOrderId,
            long makerUserId) {
        if (!active() || takerUserId <= 0 || takerOrderId <= 0 || takerSymbolId < 0 || takerSide == null) return;
        String id =
                product
                        + ":"
                        + sequence
                        + ":"
                        + matcherSequence
                        + ":"
                        + takerOrderId
                        + ":"
                        + fillIndex;
        String instrumentId = identities.instrumentId(takerSymbolId);
        outbox.stage(RealtimeFrameCodec.encodeTradePayload(
                product, sequence, ordinal++, timestamp, snapshotId, instrumentId, id,
                price, quantity, matcherSequence, (byte) takerSide.ordinal()));
        execution(
                takerUserId,
                takerOrderId,
                instrumentId,
                id,
                price,
                quantity,
                takerSide,
                false);
        execution(
                makerUserId,
                makerOrderId,
                instrumentId,
                id,
                price,
                quantity,
                takerSide == CoreOrderSide.BUY ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                true);
    }

    private void execution(
            long user,
            long order,
            String instrumentId,
            String id,
            long price,
            long quantity,
            CoreOrderSide side,
            boolean maker) {
        outbox.stage(RealtimeFrameCodec.encodeExecutionPayload(
                product, user, sequence, ordinal++, timestamp, snapshotId, instrumentId, id,
                order, price, quantity, (byte) side.ordinal(), maker));
    }

    public void risk(RiskSnapshotRuntime risk) {
        if (orderProjectionOnly) return;
        if (!active() || risk == null) return;
        String instrumentId = identities.instrumentId(risk.symbolId());
        outbox.stage(RealtimeFrameCodec.encodeRiskPayload(
                product, risk.userId(), sequence, ordinal++, timestamp, snapshotId, instrumentId,
                instrumentId + ":" + risk.positionSide(),
                risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                risk.maintenanceMarginUnits(), risk.marginRatioPpm(),
                (byte) risk.positionSide().ordinal(), (byte) risk.status().ordinal()));
    }
}
