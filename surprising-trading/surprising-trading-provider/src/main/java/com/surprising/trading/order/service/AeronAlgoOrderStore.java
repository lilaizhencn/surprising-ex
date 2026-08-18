package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
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

    public AlgoOrderRecord upsert(AlgoOrderRecord record, List<Long> childOrderIds, long revision) {
        CoreAlgoOrderView view = view(record, childOrderIds, revision, 0, 0, 0);
        UUID commandId = revision == 1
                ? StableOrderIdentity.algoCommandId(record.productLine(), record.userId(), record.clientAlgoOrderId())
                : UUID.nameUUIDFromBytes(("ALGO:" + record.algoOrderId() + ':' + revision)
                        .getBytes(StandardCharsets.UTF_8));
        aeron.command(CoreMessageType.UPSERT_ALGO_ORDER, commandId, record.userId(), CoreAlgoOrderCodec.encode(view));
        CoreAlgoOrderView persisted = aeron.algoOrder(record.userId(), record.algoOrderId());
        if (persisted == null) throw new IllegalStateException("algo order missing after Aeron upsert");
        return record(persisted);
    }

    public CoreAlgoOrderView tryClaim(CoreAlgoOrderView current, AlgoOrderRecord claimed) {
        CoreAlgoOrderView next = view(claimed, current.childOrderIds(), current.revision() + 1, 0, 0, 0);
        boolean applied = aeron.tryCommand(CoreMessageType.UPSERT_ALGO_ORDER, UUID.randomUUID(), claimed.userId(),
                CoreAlgoOrderCodec.encode(next));
        return applied ? aeron.algoOrder(claimed.userId(), claimed.algoOrderId()) : null;
    }

    public CoreAlgoOrderView get(long userId, long id) {
        CoreAlgoOrderView value = aeron.algoOrder(userId, id);
        if (value == null) throw new IllegalStateException("算法单不存在: " + id);
        return value;
    }
    public List<CoreAlgoOrderView> query(long userId, String symbol, long dueAt, int limit) {
        return aeron.algoOrders(userId, symbol, dueAt, limit);
    }
    public AlgoOrderRecord record(CoreAlgoOrderView value) {
        return new AlgoOrderRecord(value.algoOrderId(), properties.getKafka().getProductLine(), value.userId(), empty(value.clientAlgoOrderId()),
                value.symbol(), AlgoOrderType.values()[value.algoTypeCode()], OrderSide.valueOf(value.side().name()),
                value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(), value.intervalSeconds(),
                value.durationSeconds(), MarginMode.valueOf(value.marginMode().name()),
                PositionSide.valueOf(value.positionSide().name()), value.reduceOnly(), value.postOnly(),
                TimeInForce.valueOf(value.timeInForce().name()), AlgoOrderStatus.values()[value.statusCode()],
                value.currentOrderId() == 0 ? null : value.currentOrderId(), empty(value.rejectReason()),
                empty(value.traceId()), instant(value.startAtEpochMillis()), instant(value.nextSliceAtEpochMillis()),
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
        return new AlgoOrderResponse(record.algoOrderId(), record.userId(), record.clientAlgoOrderId(), record.symbol(),
                record.algoType(), record.side(), record.priceTicks(), record.quantitySteps(), record.childQuantitySteps(),
                record.intervalSeconds(), record.durationSeconds(), record.marginMode(), record.positionSide(), record.reduceOnly(),
                record.postOnly(), record.timeInForce(), record.status(), progress.executedQuantitySteps(),
                progress.activeQuantitySteps(), progress.childOrderCount(), record.currentOrderId(), record.rejectReason(),
                record.startAt(), record.nextSliceAt(), record.completedAt(), record.createdAt(), record.updatedAt());
    }
    private CoreAlgoOrderView view(AlgoOrderRecord value, List<Long> children, long revision,
                                   long executed, long active, int activeCount) {
        return new CoreAlgoOrderView(value.algoOrderId(), value.userId(), text(value.clientAlgoOrderId()), value.symbol(),
                value.algoType().ordinal(), CoreOrderSide.valueOf(value.side().name()), value.priceTicks(), value.quantitySteps(),
                value.childQuantitySteps(), value.intervalSeconds(), value.durationSeconds(),
                CoreMarginMode.valueOf(value.marginMode().name()), CorePositionSide.valueOf(value.positionSide().name()),
                value.reduceOnly(), value.postOnly(), CoreTimeInForce.valueOf(value.timeInForce().name()), value.status().ordinal(),
                value.currentOrderId() == null ? 0 : value.currentOrderId(), text(value.rejectReason()), text(value.traceId()),
                epoch(value.startAt()), epoch(value.nextSliceAt()), epoch(value.completedAt()), epoch(value.createdAt()),
                epoch(value.updatedAt()), revision, children, executed, active, activeCount);
    }
    private static long epoch(Instant value) { return value == null ? 0 : value.toEpochMilli(); }
    private static Instant instant(long value) { return value == 0 ? null : Instant.ofEpochMilli(value); }
    private static String text(String value) { return value == null ? "" : value; }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
}
