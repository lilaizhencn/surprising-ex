package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.surprising.trading.order.config.TradingOrderProperties;

@Service
public class AeronAlgoOrderStore {
    private final OrderAeronGateway aeron;
    private final TradingOrderProperties properties;

    public AeronAlgoOrderStore(OrderAeronGateway aeron, TradingOrderProperties properties) {
        this.aeron = aeron;
        this.properties = properties;
    }

    public AlgoOrderRecord create(AlgoOrderRecord record) {
        var command = new CoreAlgoOrderCommand.Create(record.algoOrderId(), text(record.clientAlgoOrderId()),
                record.instrumentId(), record.algoType().ordinal(), CoreOrderSide.valueOf(record.side().name()),
                record.priceTicks(), record.quantitySteps(), record.childQuantitySteps(), record.intervalSeconds(),
                record.durationSeconds(), CoreMarginMode.valueOf(record.marginMode().name()),
                CorePositionSide.valueOf(record.positionSide().name()), record.reduceOnly(), record.postOnly(),
                CoreTimeInForce.valueOf(record.timeInForce().name()), epoch(record.startAt()));
        aeron.command(CoreMessageType.UPSERT_ALGO_ORDER,
                StableOrderIdentity.algoCommandId(record.productLine(), record.userId(), record.clientAlgoOrderId()),
                record.userId(), CoreAlgoOrderCodec.encodeCommand(command));
        return record(get(record.userId(), record.algoOrderId()));
    }

    public void update(AlgoOrderRecord record, long expectedRevision, long appendedChildOrderId) {
        UUID commandId = UUID.nameUUIDFromBytes(("ALGO:" + record.algoOrderId() + ':'
                + Math.incrementExact(expectedRevision)).getBytes(StandardCharsets.UTF_8));
        aeron.command(CoreMessageType.UPSERT_ALGO_ORDER, commandId, record.userId(),
                CoreAlgoOrderCodec.encodeCommand(updateCommand(record, expectedRevision, appendedChildOrderId)));
    }

    public CoreAlgoOrderView tryClaim(CoreAlgoOrderView current, AlgoOrderRecord claimed) {
        boolean applied = aeron.tryCommand(CoreMessageType.UPSERT_ALGO_ORDER, UUID.randomUUID(), claimed.userId(),
                CoreAlgoOrderCodec.encodeCommand(updateCommand(claimed, current.revision(), 0)));
        return applied ? aeron.algoOrder(claimed.userId(), claimed.algoOrderId()) : null;
    }

    private static CoreAlgoOrderCommand.Update updateCommand(AlgoOrderRecord value, long revision, long appendedChild) {
        return new CoreAlgoOrderCommand.Update(value.algoOrderId(), revision, value.status().ordinal(),
                value.currentOrderId() == null ? 0 : value.currentOrderId(), text(value.rejectReason()),
                epoch(value.nextSliceAt()), epoch(value.completedAt()), epoch(value.updatedAt()), appendedChild);
    }

    public CoreAlgoOrderView get(long userId, long id) {
        CoreAlgoOrderView value = aeron.algoOrder(userId, id);
        if (value == null) throw new IllegalStateException("算法单不存在: " + id);
        return value;
    }
    /** Internal trading recovery; the immutable child key makes an unknown placement discoverable. */
    public com.surprising.aeron.protocol.CoreOrderStateView unlinkedChild(CoreAlgoOrderView parent) {
        return aeron.orderStateByClientOrderId(parent.userId(), "algo-"+parent.algoOrderId()+"-"+parent.childOrderIds().size());
    }
    public List<CoreAlgoOrderView> query(long userId, String instrumentId, long dueAt, int limit) {
        return aeron.algoOrders(userId, instrumentId, dueAt, limit);
    }
    public AlgoOrderRecord record(CoreAlgoOrderView value) {
        var productLine = properties.getKafka().getProductLine();

        return new AlgoOrderRecord(value.algoOrderId(), productLine, value.userId(), empty(value.clientAlgoOrderId()),
                value.instrumentId(), AlgoOrderType.values()[value.algoTypeCode()], OrderSide.valueOf(value.side().name()),
                value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(), value.intervalSeconds(),
                value.durationSeconds(), MarginMode.valueOf(value.marginMode().name()),
                PositionSide.valueOf(value.positionSide().name()), value.reduceOnly(), value.postOnly(),
                TimeInForce.valueOf(value.timeInForce().name()), AlgoOrderStatus.values()[value.statusCode()],
                value.currentOrderId() == 0 ? null : value.currentOrderId(), empty(value.rejectReason()),
                instant(value.startAtEpochMillis()), instant(value.nextSliceAtEpochMillis()),
                instant(value.completedAtEpochMillis()), instant(value.createdAtEpochMillis()), instant(value.updatedAtEpochMillis()));
    }
    public AlgoOrderProgress progress(CoreAlgoOrderView value) {
        return new AlgoOrderProgress(value.executedQuantitySteps(), value.activeQuantitySteps(),
                value.childOrderIds().size(), value.activeChildOrderCount(), value.childOrderIds().size());
    }
    public List<AlgoOrderChild> children(CoreAlgoOrderView value) {
        return java.util.stream.IntStream.range(0, value.childOrderIds().size())
                .mapToObj(index -> new AlgoOrderChild(value.algoOrderId(), index, value.childOrderIds().get(index),
                        value.childQuantitySteps())).toList();
    }
    public AlgoOrderResponse response(CoreAlgoOrderView value) {
        AlgoOrderRecord record = record(value); AlgoOrderProgress progress = progress(value);
        return new AlgoOrderResponse(record.algoOrderId(), record.userId(), record.clientAlgoOrderId(), record.instrumentId(),
                record.algoType(), record.side(), record.priceTicks(), record.quantitySteps(), record.childQuantitySteps(),
                record.intervalSeconds(), record.durationSeconds(), record.marginMode(), record.positionSide(), record.reduceOnly(),
                record.postOnly(), record.timeInForce(), record.status(), progress.executedQuantitySteps(),
                progress.activeQuantitySteps(), progress.childOrderCount(), record.currentOrderId(), record.rejectReason(),
                record.startAt(), record.nextSliceAt(), record.completedAt(), record.createdAt(), record.updatedAt());
    }

    private static long epoch(Instant value) { return value == null ? 0 : value.toEpochMilli(); }
    private static Instant instant(long value) { return value == 0 ? null : Instant.ofEpochMilli(value); }
    private static String text(String value) { return value == null ? "" : value; }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
}
