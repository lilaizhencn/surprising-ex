package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.service.LiquidationInstrumentSnapshotService.InstrumentFee;
import com.surprising.liquidation.provider.service.LiquidationInstrumentSnapshotService.InstrumentFeeRequest;
import com.surprising.liquidation.provider.repository.LiquidationOrderEventRepository;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.NewLiquidationOrder;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.OpenReduceOnlyOrder;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.OrderScope;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.liquidation.provider.repository.LiquidationTradingOutboxRepository;
import com.surprising.liquidation.provider.repository.LiquidationTradingOutboxRepository.NewOutboxEvent;
import com.surprising.liquidation.provider.repository.LiquidationUserFeeRepository;
import com.surprising.liquidation.provider.repository.LiquidationUserFeeRepository.UserFee;
import com.surprising.liquidation.provider.repository.LiquidationUserFeeRepository.UserFeeRequest;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 强平订单持久化编排服务。
 *
 * <p>在同一事务中协调交易订单、订单事件和 outbox 三个单表仓储，保证订单落库与消息发布意图原子提交。
 */
@Service
public class LiquidationOrderPersistenceService {

    private static final String REDUCE_ONLY_PREEMPT_REASON = "LIQUIDATION_PREEMPTED_REDUCE_ONLY";

    private final LiquidationOrderRepository orderRepository;
    private final LiquidationOrderEventRepository eventRepository;
    private final LiquidationTradingOutboxRepository outboxRepository;
    private final LiquidationInstrumentSnapshotService instrumentSnapshotService;
    private final LiquidationUserFeeRepository userFeeRepository;
    private final LiquidationSequenceRepository sequenceRepository;
    private final LiquidationProperties properties;

    public LiquidationOrderPersistenceService(
            LiquidationOrderRepository orderRepository,
            LiquidationOrderEventRepository eventRepository,
            LiquidationTradingOutboxRepository outboxRepository,
            LiquidationInstrumentSnapshotService instrumentSnapshotService,
            LiquidationUserFeeRepository userFeeRepository,
            LiquidationSequenceRepository sequenceRepository,
            LiquidationProperties properties) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.instrumentSnapshotService = instrumentSnapshotService;
        this.userFeeRepository = userFeeRepository;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    @Transactional
    public List<LiquidationOrderSubmission> createReduceOnlyMarketOrders(
            List<LiquidationOrderRequest> requests, Function<Object, String> serializer) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Map<Long, FeeSnapshot> feeSnapshots = feeSnapshots(requests);
        List<PreparedLiquidationOrder> prepared = new ArrayList<>(requests.size());
        for (LiquidationOrderRequest request : requests) {
            FeeSnapshot fee = feeSnapshots.get(request.candidateId());
            if (fee == null) {
                throw new IllegalStateException("强平候选缺少可用费率：" + request.candidateId());
            }
            long orderId = sequenceRepository.nextTradingSequence("order");
            long eventId = sequenceRepository.nextTradingSequence("event");
            long commandId = sequenceRepository.nextTradingSequence("command");
            long acceptedOutboxId = sequenceRepository.nextTradingSequence("outbox");
            long placeOutboxId = sequenceRepository.nextTradingSequence("outbox");
            String clientOrderId = "LIQ-" + request.candidateId();
            OrderEvent event = new OrderEvent(eventId, orderId, request.userId(), request.symbol(),
                    OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, "LIQUIDATION", request.now());
            OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId,
                    request.userId(), clientOrderId, request.symbol(), request.instrumentVersion(), request.side(),
                    OrderType.MARKET, TimeInForce.IOC, 0L, request.quantitySteps(), request.marginMode(),
                    request.positionSide(), 0L, 0L, true, false, request.now(), null);
            prepared.add(new PreparedLiquidationOrder(request, orderId, clientOrderId, fee, event, command,
                    acceptedOutboxId, placeOutboxId, serializer.apply(event), serializer.apply(command)));
        }
        orderRepository.insertAll(prepared.stream().map(this::toOrder).toList());
        eventRepository.insertAll(prepared.stream().map(PreparedLiquidationOrder::event).toList());
        outboxRepository.insertAll(prepared.stream().flatMap(row -> List.of(
                new NewOutboxEvent(row.acceptedOutboxId(), "LIQUIDATION_ORDER", row.orderId(),
                        properties.getKafka().getOrderEventsTopic(), row.request().symbol(),
                        OrderEventType.ACCEPTED.name(), row.eventPayload(), row.request().now()),
                new NewOutboxEvent(row.placeOutboxId(), "LIQUIDATION_ORDER", row.orderId(),
                        properties.getKafka().getOrderCommandsTopic(), row.request().symbol(),
                        OrderCommandType.PLACE.name(), row.commandPayload(), row.request().now())
        ).stream()).toList());
        return prepared.stream().map(row -> new LiquidationOrderSubmission(
                row.request().candidateId(), row.command())).toList();
    }

    @Transactional
    public int cancelOpenReduceOnlyCloseOrders(
            List<LiquidationOrderRequest> requests, Function<Object, String> serializer) {
        if (requests == null || requests.isEmpty()) {
            return 0;
        }
        List<OrderScope> scopes = requests.stream().map(request -> new OrderScope(
                request.userId(), request.symbol(), request.marginMode(), request.positionSide(),
                request.instrumentVersion(), request.side(), request.now())).toList();
        List<OpenReduceOnlyOrder> orders = orderRepository.lockOpenReduceOnlyCloseOrders(scopes);
        for (OpenReduceOnlyOrder order : orders) {
            if (order.status() != OrderStatus.CANCEL_REQUESTED) {
                requestCancel(order, serializer);
            }
            enqueueCancelCommand(order, serializer);
        }
        return orders.size();
    }

    private void requestCancel(OpenReduceOnlyOrder order, Function<Object, String> serializer) {
        orderRepository.requestCancel(order.orderId(), REDUCE_ONLY_PREEMPT_REASON, order.preemptedAt());
        OrderEvent event = new OrderEvent(sequenceRepository.nextTradingSequence("event"), order.orderId(),
                order.userId(), order.symbol(), OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED,
                REDUCE_ONLY_PREEMPT_REASON, order.preemptedAt());
        eventRepository.insert(event);
        outboxRepository.insert(new NewOutboxEvent(
                sequenceRepository.nextTradingSequence("outbox"), "ORDER", order.orderId(),
                properties.getKafka().getOrderEventsTopic(), order.symbol(), OrderEventType.CANCEL_REQUESTED.name(),
                serializer.apply(event), order.preemptedAt()));
    }

    private void enqueueCancelCommand(OpenReduceOnlyOrder order, Function<Object, String> serializer) {
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.CANCEL,
                sequenceRepository.nextTradingSequence("command"), order.orderId(), order.userId(),
                order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(), order.orderType(),
                order.timeInForce(), order.priceTicks(), order.quantitySteps(), order.marginMode(),
                order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(), true,
                order.postOnly(), order.preemptedAt(), null);
        outboxRepository.insert(new NewOutboxEvent(
                sequenceRepository.nextTradingSequence("outbox"), "ORDER", order.orderId(),
                properties.getKafka().getOrderCommandsTopic(), order.symbol(), OrderCommandType.CANCEL.name(),
                serializer.apply(command), order.preemptedAt()));
    }

    private Map<Long, FeeSnapshot> feeSnapshots(List<LiquidationOrderRequest> requests) {
        Map<Long, InstrumentFee> defaults = instrumentSnapshotService.findAll(requests.stream()
                .map(request -> new InstrumentFeeRequest(
                        request.candidateId(), request.symbol(), request.instrumentVersion()))
                .toList());
        if (defaults.size() != requests.size()) {
            throw new IllegalStateException("强平批次缺少合约默认费率");
        }
        Map<Long, LiquidationOrderRequest> requestByCandidate = new HashMap<>();
        requests.forEach(request -> requestByCandidate.put(request.candidateId(), request));
        Map<Long, UserFee> overrides = userFeeRepository.findBestActive(defaults.values().stream()
                .map(fee -> {
                    LiquidationOrderRequest request = requestByCandidate.get(fee.candidateId());
                    return new UserFeeRequest(fee.candidateId(), request.userId(), request.symbol(),
                            fee.productLine(), request.now());
                }).toList());
        Map<Long, FeeSnapshot> snapshots = new HashMap<>(defaults.size());
        defaults.forEach((candidateId, fee) -> {
            UserFee override = overrides.get(candidateId);
            snapshots.put(candidateId, override == null
                    ? new FeeSnapshot(fee.productLine(), fee.makerFeeRatePpm(), fee.takerFeeRatePpm())
                    : new FeeSnapshot(fee.productLine(), override.makerFeeRatePpm(), override.takerFeeRatePpm()));
        });
        return snapshots;
    }

    private NewLiquidationOrder toOrder(PreparedLiquidationOrder row) {
        return new NewLiquidationOrder(row.orderId(), row.fee().productLine(), row.request().userId(),
                row.clientOrderId(), row.request().symbol(), row.request().instrumentVersion(), row.request().side(),
                row.request().quantitySteps(), row.request().marginMode(), row.request().positionSide(),
                row.fee().makerFeeRatePpm(), row.fee().takerFeeRatePpm(), row.request().now());
    }

    public record LiquidationOrderRequest(long candidateId,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          PositionSide positionSide,
                                          long instrumentVersion,
                                          OrderSide side,
                                          long quantitySteps,
                                          Instant now) {
        public LiquidationOrderRequest {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    public record LiquidationOrderSubmission(long candidateId, OrderCommandEvent command) {
    }

    private record FeeSnapshot(ProductLine productLine, long makerFeeRatePpm, long takerFeeRatePpm) {
    }

    private record PreparedLiquidationOrder(LiquidationOrderRequest request,
                                            long orderId,
                                            String clientOrderId,
                                            FeeSnapshot fee,
                                            OrderEvent event,
                                            OrderCommandEvent command,
                                            long acceptedOutboxId,
                                            long placeOutboxId,
                                            String eventPayload,
                                            String commandPayload) {
    }
}
