package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserEvent;
import com.surprising.trading.order.model.OrderUserState;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单用户分区的单写者状态机。
 *
 * <p>下单、账户预占结果、撤单和撮合结果都先进入同一个用户 WAL，再由本地状态快照按序应用。
 * Kafka 只承担跨模块通知；数据库不参与订单状态裁决。外部重复消息由 WAL 事件编号和状态机
 * 的 appliedEventIds 双重幂等。</p>
 */
@Service
public class OrderUserStateService {

    private static final Logger log = LoggerFactory.getLogger(OrderUserStateService.class);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionCommandLane lane;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;
    private final OrderIdSequenceStore orderIdSequenceStore;
    private long lastTimestamp;
    private int sequence;

    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, wal, stateStore, lane, kafkaTemplate, null, null);
    }

    @Autowired
    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 @Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                 @Nullable OrderIdSequenceStore orderIdSequenceStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.stateStore = stateStore;
        this.lane = lane;
        this.kafkaTemplate = kafkaTemplate;
        this.accountStateSnapshotCache = accountStateSnapshotCache;
        this.orderIdSequenceStore = orderIdSequenceStore;
    }

    /** 订单编号不依赖数据库序列；低两位预留给同一订单的命令编号。 */
    public synchronized long nextOrderId() {
        if (orderIdSequenceStore != null) {
            return orderIdSequenceStore.next();
        }
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            if (sequence >= 1_023) {
                // 同一毫秒的 1024 个编号已用尽，逻辑时钟前移一毫秒，避免自旋和重复编号。
                now = Math.addExact(lastTimestamp, 1L);
                sequence = 0;
            } else {
                sequence++;
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;
        long current = sequence;
        long value = Math.addExact(Math.multiplyExact(now, 1L << 22),
                Math.addExact(((long) properties.getWal().getNodeId()) << 12, current << 2));
        if (value <= 0L) {
            throw new IllegalStateException("订单编号溢出");
        }
        return value;
    }

    public OrderResponse place(OrderRecord order) {
        UserPartitionKey partition = partition(order.productLine(), order.userId());
        return lane.execute(partition, () -> {
            OrderUserState current = state(partition);
            Optional<OrderRecord> duplicate = current.orders().stream()
                    .filter(value -> value.clientOrderId() != null
                            && value.clientOrderId().equals(order.clientOrderId()))
                    .findFirst();
            if (duplicate.isPresent()) {
                requireSameIntent(duplicate.get(), order);
                return toResponse(duplicate.get());
            }
            validateMarginModeInPartition(current, order);
            append(partition, OrderUserEvent.place(order));
            return toResponse(order);
        });
    }

    /** 同一用户分区内原子检查保证金模式，避免两个并发下单在检查与写入之间交错。 */
    public boolean hasActiveMarginModeConflict(long userId, String symbol, com.surprising.trading.api.model.MarginMode marginMode) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> state(partition).orders().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.status() != OrderStatus.CANCELED
                        && value.status() != OrderStatus.REJECTED
                        && value.status() != OrderStatus.FILLED)
                .anyMatch(value -> value.marginMode() != com.surprising.trading.api.model.MarginMode.defaultIfNull(marginMode)));
    }

    private void validateMarginModeInPartition(OrderUserState current, OrderRecord order) {
        if (order.status() == OrderStatus.REJECTED
                || order.productLine() != ProductLine.LINEAR_PERPETUAL) {
            return;
        }
        var normalized = com.surprising.trading.api.model.MarginMode.defaultIfNull(order.marginMode());
        boolean orderConflict = current.orders().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(order.symbol()))
                .filter(value -> value.status() != OrderStatus.CANCELED
                        && value.status() != OrderStatus.REJECTED
                        && value.status() != OrderStatus.FILLED)
                .anyMatch(value -> value.marginMode() != normalized);
        if (orderConflict) {
            throw new IllegalStateException("保证金模式冲突，必须先关闭已有仓位或订单");
        }
        if (accountStateSnapshotCache == null) {
            return;
        }
        var snapshot = accountStateSnapshotCache.state(order.userId())
                .orElseThrow(() -> new IllegalStateException("永续账户状态快照尚未就绪: " + order.userId()));
        boolean positionConflict = snapshot.positions().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(order.symbol()))
                .filter(value -> value.signedQuantitySteps() != 0L)
                .anyMatch(value -> value.marginMode() != normalized);
        if (positionConflict) {
            throw new IllegalStateException("保证金模式冲突，必须先关闭已有仓位或订单");
        }
    }

    public OrderResponse cancel(long userId, long orderId, String reason) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            OrderUserState current = state(partition);
            OrderRecord order = find(current, orderId);
            if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.FILLED
                    || order.status() == OrderStatus.REJECTED || order.status() == OrderStatus.CANCEL_REQUESTED) {
                return toResponse(order);
            }
            append(partition, OrderUserEvent.cancel(orderId, reason));
            return toResponse(withStatus(order, OrderStatus.CANCEL_REQUESTED, null));
        });
    }

    public OrderResponse get(long userId, long orderId) {
        return toResponse(find(state(partition(properties.getKafka().getProductLine(), userId)), orderId));
    }

    public OrderResponse get(long orderId) {
        for (UserPartitionKey partition : stateStore.partitions()) {
            Optional<OrderRecord> found = state(partition).orders().stream()
                    .filter(value -> value.orderId() == orderId).findFirst();
            if (found.isPresent()) {
                return toResponse(found.orElseThrow());
            }
        }
        throw new IllegalStateException("订单不存在: " + orderId);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        OrderUserState state = state(partition(properties.getKafka().getProductLine(), userId));
        return state.orders().stream().filter(value -> clientOrderId.equals(value.clientOrderId())).findFirst()
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("order not found for clientOrderId: " + clientOrderId));
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, long beforeOrderId) {
        String normalized = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        List<OrderResponse> orders = state(partition(properties.getKafka().getProductLine(), userId)).orders().stream()
                .filter(value -> value.orderId() < beforeOrderId || beforeOrderId <= 0L)
                .filter(value -> normalized == null || value.symbol().equals(normalized))
                .filter(value -> value.status() != OrderStatus.CANCELED && value.status() != OrderStatus.REJECTED
                        && value.status() != OrderStatus.FILLED)
                .sorted(java.util.Comparator.comparingLong(OrderRecord::orderId).reversed())
                .limit(limit + 1L)
                .map(this::toResponse)
                .toList();
        boolean more = orders.size() > limit;
        List<OrderResponse> page = more ? orders.subList(0, limit) : orders;
        String cursor = more ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("order:" + page.getLast().orderId()).getBytes(StandardCharsets.UTF_8)) : null;
        return new OrderQueryResponse(page.size(), page, cursor, more, "orderId.desc", limit);
    }

    public List<OrderResponse> cancelOpenOrders(long userId, String symbol, int limit) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            List<OrderRecord> orders = state(partition).orders().stream()
                    .filter(value -> symbol == null || value.symbol().equalsIgnoreCase(symbol))
                    .filter(value -> value.status() != OrderStatus.CANCELED && value.status() != OrderStatus.REJECTED
                            && value.status() != OrderStatus.FILLED
                            && value.status() != OrderStatus.CANCEL_REQUESTED)
                    .sorted(java.util.Comparator.comparingLong(OrderRecord::orderId).reversed())
                    .limit(limit)
                    .toList();
            List<OrderResponse> responses = new ArrayList<>(orders.size());
            for (OrderRecord order : orders) {
                append(partition, OrderUserEvent.cancel(order.orderId(), "USER_CANCEL_ALL"));
                responses.add(toResponse(withStatus(order, OrderStatus.CANCEL_REQUESTED, null)));
            }
            return List.copyOf(responses);
        });
    }

    /** 账户结果消费只追加用户订单事实，不回查订单表。 */
    public void processAccountCommandResults(List<AccountCommandResultEvent> results) {
        if (results == null) {
            return;
        }
        for (AccountCommandResultEvent result : results) {
            if (result == null || result.commandType() != AccountUserCommandType.ORDER_RESERVE
                    || !"ORDER".equals(result.source())) {
                continue;
            }
            UserPartitionKey partition = partition(result.productLine(), result.userId());
            lane.execute(partition, () -> {
                append(partition, OrderUserEvent.accountResult(result));
                return null;
            });
        }
    }

    /** 撮合结果按成交参与方分别写入对应用户分区。 */
    public void processMatchResults(List<MatchResultEvent> results) {
        if (results == null) {
            return;
        }
        for (MatchResultEvent result : results) {
            if (result == null) {
                continue;
            }
            appendMatch(result.userId(), result);
            for (MatchTradeEvent trade : result.trades()) {
                if (trade.makerUserId() != result.userId()) {
                    appendMatch(trade.makerUserId(), result);
                }
            }
        }
    }

    private void appendMatch(long userId, MatchResultEvent result) {
        UserPartitionKey partition = partition(result.symbol(), userId);
        lane.execute(partition, () -> {
            append(partition, OrderUserEvent.matchResult(result));
            return null;
        });
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.wal.worker-delay-ms:25}")
    public void applyPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                lane.execute(partition, () -> applyPartition(partition));
            } catch (RuntimeException ex) {
                log.warn("订单事实流分区执行失败 partition={}", partition.value(), ex);
            }
        }
    }

    private Void applyPartition(UserPartitionKey partition) {
        OrderUserState current = state(partition);
        long applied = stateStore.lastAppliedSequence(partition);
        for (UserPartitionEvent raw : wal.replay(partition)) {
            if (raw.sequence() <= applied) {
                continue;
            }
            if (raw.sequence() != applied + 1L) {
                throw new IllegalStateException("订单事实流序号断裂 partition=" + partition.value());
            }
            OrderUserEvent event = decode(raw);
            if (current.appliedEventIds().contains(event.eventId())) {
                stateStore.apply(partition, raw.sequence(), serialize(current));
                applied = raw.sequence();
                continue;
            }
            current = applyEvent(current, event);
            List<String> eventIds = new ArrayList<>(current.appliedEventIds());
            eventIds.add(event.eventId());
            current = new OrderUserState(current.orders(), eventIds);
            stateStore.apply(partition, raw.sequence(), serialize(current));
            applied = raw.sequence();
        }
        return null;
    }

    private OrderUserState applyEvent(OrderUserState current, OrderUserEvent event) {
        return switch (event.eventType()) {
            case "PLACE" -> applyPlace(current, event.order());
            case "ACCOUNT_RESULT" -> applyAccountResult(current, event.accountResult());
            case "MATCH_RESULT" -> applyMatchResult(current, event.matchResult());
            case "CANCEL" -> applyCancel(current, event);
            default -> throw new IllegalStateException("未知订单事实事件: " + event.eventType());
        };
    }

    private OrderUserState applyPlace(OrderUserState current, OrderRecord order) {
        if (current.orders().stream().anyMatch(value -> value.orderId() == order.orderId())) {
            return current;
        }
        publishForPlace(order);
        List<OrderRecord> orders = new ArrayList<>(current.orders());
        orders.add(order);
        return new OrderUserState(orders, current.appliedEventIds());
    }

    private OrderUserState applyAccountResult(OrderUserState current, AccountCommandResultEvent result) {
        if (result == null) {
            throw new IllegalStateException("账户结果不能为空");
        }
        long orderId = parseOrderId(result.sourceReference());
        OrderRecord order = find(current, orderId);
        if (order.status() != OrderStatus.PENDING_RESERVE) {
            return current;
        }
        boolean accepted = result.status() == AccountCommandStatus.APPLIED;
        OrderRecord updated = withStatus(order, accepted ? OrderStatus.ACCEPTED : OrderStatus.REJECTED,
                accepted ? null : (result.errorMessage() == null ? result.errorCode() : result.errorMessage()));
        if (accepted) {
            publishOrderCommand(updated, OrderCommandType.PLACE);
            publishOrderEvent(updated, OrderEventType.ACCEPTED, null);
        } else {
            publishOrderEvent(updated, OrderEventType.REJECTED, updated.rejectReason());
        }
        return replace(current, updated);
    }

    private OrderUserState applyMatchResult(OrderUserState current, MatchResultEvent result) {
        if (result == null) {
            throw new IllegalStateException("撮合结果不能为空");
        }
        java.util.Map<Long, Long> makerFills = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> makerCompletions = new java.util.HashMap<>();
        for (MatchTradeEvent trade : result.trades()) {
            boolean belongsToCurrentUser = current.orders().stream()
                    .anyMatch(value -> value.orderId() == trade.makerOrderId()
                            && value.userId() == trade.makerUserId());
            if (!belongsToCurrentUser) {
                continue;
            }
            makerFills.merge(trade.makerOrderId(), trade.quantitySteps(), Math::addExact);
            makerCompletions.merge(trade.makerOrderId(), trade.makerOrderCompleted(), Boolean::logicalOr);
        }
        List<OrderRecord> updatedOrders = new ArrayList<>(current.orders().size());
        boolean touched = false;
        for (OrderRecord order : current.orders()) {
            long filled = 0L;
            OrderStatus nextStatus = null;
            if (order.orderId() == result.orderId() && order.userId() == result.userId()) {
                filled = result.filledQuantitySteps();
                nextStatus = result.orderStatus();
            } else if (makerFills.containsKey(order.orderId())) {
                filled = makerFills.get(order.orderId());
                nextStatus = makerCompletions.getOrDefault(order.orderId(), false)
                        ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            }
            if (nextStatus == null) {
                updatedOrders.add(order);
                continue;
            }
            if (filled < 0L || Math.addExact(order.executedQuantitySteps(), filled) > order.quantitySteps()) {
                throw new IllegalStateException("撮合成交数量超过订单数量 orderId=" + order.orderId());
            }
            long executed = Math.addExact(order.executedQuantitySteps(), filled);
            long remaining = Math.subtractExact(order.quantitySteps(), executed);
            updatedOrders.add(new OrderRecord(order.orderId(), order.productLine(), order.userId(),
                    order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                    order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(), executed,
                    remaining, order.marginMode(), order.positionSide(), order.makerFeeRatePpm(),
                    order.takerFeeRatePpm(), order.reduceOnly(), order.postOnly(), order.reservationAccountType(),
                    order.reservationAsset(), order.reservedUnits(), nextStatus, order.rejectReason(),
                    order.createdAt(), Instant.now(), Math.addExact(order.revision(), 1L)));
            touched = true;
        }
        if (!touched) {
            throw new IllegalStateException("撮合结果对应订单不存在: " + result.orderId());
        }
        return new OrderUserState(updatedOrders, current.appliedEventIds());
    }

    private OrderUserState applyCancel(OrderUserState current, OrderUserEvent event) {
        long orderId = parseOrderId(event.eventId().substring("CANCEL:".length()));
        OrderRecord order = find(current, orderId);
        if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.FILLED
                || order.status() == OrderStatus.REJECTED) {
            return current;
        }
        OrderRecord updated = withStatus(order, OrderStatus.CANCEL_REQUESTED, null);
        publishOrderCommand(updated, OrderCommandType.CANCEL);
        publishOrderEvent(updated, OrderEventType.CANCEL_REQUESTED, event.cancelReason());
        return replace(current, updated);
    }

    private void publishForPlace(OrderRecord order) {
        if (order.status() == OrderStatus.PENDING_RESERVE && order.reservedUnits() > 0L) {
            publishAccountReservation(order);
        } else if (order.status() == OrderStatus.ACCEPTED) {
            publishOrderCommand(order, OrderCommandType.PLACE);
            publishOrderEvent(order, OrderEventType.ACCEPTED, null);
        } else if (order.status() == OrderStatus.REJECTED) {
            publishOrderEvent(order, OrderEventType.REJECTED, order.rejectReason());
        }
    }

    private void publishAccountReservation(OrderRecord order) {
        AccountType accountType = AccountType.valueOf(order.reservationAccountType());
        OrderReservationKind kind = accountType == AccountType.SPOT
                ? OrderReservationKind.SPOT_ASSET : OrderReservationKind.DERIVATIVE_MARGIN;
        OrderReserveAccountCommand reserve = new OrderReserveAccountCommand(order.orderId(), order.symbol(),
                order.side(), kind, accountType, order.reservationAsset(), order.marginMode(), order.positionSide(),
                order.quantitySteps(), order.reduceOnly(), order.reservedUnits());
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                reservationCommandId(order.productLine(), order.orderId()), order.productLine(), order.userId(),
                AccountUserCommandType.ORDER_RESERVE, "ORDER", String.valueOf(order.orderId()), null,
                objectMapper.writeValueAsString(reserve), Instant.now(), null);
        send(properties.getKafka().getAccountUserCommandsTopic(), command.partitionKey(),
                objectMapper.writeValueAsString(command), command.commandId());
    }

    private void publishOrderCommand(OrderRecord order, OrderCommandType type) {
        long commandId = commandId(order.orderId(), type);
        OrderCommandEvent command = new OrderCommandEvent(type, commandId, order.orderId(), order.userId(),
                order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(), order.orderType(),
                order.timeInForce(), order.priceTicks(), order.quantitySteps(), order.marginMode(),
                order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.reduceOnly(),
                order.postOnly(), order.reservationAccountType(), order.reservationAsset(), order.reservedUnits(),
                Instant.now(), null);
        send(properties.getKafka().getOrderCommandsTopic(), order.symbol(), objectMapper.writeValueAsString(command),
                "ORDER_COMMAND:" + commandId);
    }

    private void publishOrderEvent(OrderRecord order, OrderEventType type, String reason) {
        OrderEvent event = new OrderEvent(orderEventId(order.orderId(), type), order.orderId(),
                order.userId(), order.symbol(), type, order.status(), reason, Instant.now(), null);
        send(properties.getKafka().getOrderEventsTopic(), order.userId() + ":" + order.orderId(),
                objectMapper.writeValueAsString(event), "ORDER_EVENT:" + order.orderId() + ":" + type.name());
    }

    private void send(String topic, String key, String payload, String identity) {
        try {
            kafkaTemplate.send(topic, key, payload).get(3L, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new KafkaException("订单事实通知发送失败: " + identity, ex);
        }
    }

    private void append(UserPartitionKey partition, OrderUserEvent event) {
        wal.append(partition, event.eventId(), event.eventType(), serialize(event), fingerprint(event), Instant.now());
    }

    private OrderUserState state(UserPartitionKey partition) {
        return stateStore.read(partition).map(value -> deserialize(value.state())).orElseGet(() -> {
            OrderUserState empty = new OrderUserState(List.of());
            stateStore.initialize(partition, serialize(empty));
            return empty;
        });
    }

    private OrderUserEvent decode(UserPartitionEvent event) {
        try {
            return objectMapper.readValue(new String(event.payload(), StandardCharsets.UTF_8), OrderUserEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException("订单事实事件无法解析: " + event.eventId(), ex);
        }
    }

    private byte[] serialize(Object value) {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }

    private OrderUserState deserialize(byte[] bytes) {
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), OrderUserState.class);
        } catch (Exception ex) {
            throw new IllegalStateException("订单用户状态损坏", ex);
        }
    }

    private String fingerprint(OrderUserEvent event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(event)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private UserPartitionKey partition(ProductLine line, long userId) {
        return new UserPartitionKey(line, userId);
    }

    private UserPartitionKey partition(String ignoredSymbol, long userId) {
        return partition(properties.getKafka().getProductLine(), userId);
    }

    private OrderRecord find(OrderUserState state, long orderId) {
        return state.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("订单不存在: " + orderId));
    }

    private OrderUserState replace(OrderUserState state, OrderRecord updated) {
        return new OrderUserState(state.orders().stream().map(value -> value.orderId() == updated.orderId()
                ? updated : value).toList(), state.appliedEventIds());
    }

    private OrderRecord withStatus(OrderRecord order, OrderStatus status, String reason) {
        return new OrderRecord(order.orderId(), order.productLine(), order.userId(), order.clientOrderId(),
                order.symbol(), order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                order.remainingQuantitySteps(), order.marginMode(), order.positionSide(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.reduceOnly(), order.postOnly(), order.reservationAccountType(),
                order.reservationAsset(), order.reservedUnits(), status, reason, order.createdAt(), Instant.now(),
                Math.addExact(order.revision(), 1L));
    }

    private void requireSameIntent(OrderRecord left, OrderRecord right) {
        if (left.userId() != right.userId() || !left.symbol().equals(right.symbol()) || left.side() != right.side()
                || left.orderType() != right.orderType() || left.timeInForce() != right.timeInForce()
                || left.priceTicks() != right.priceTicks() || left.quantitySteps() != right.quantitySteps()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()) {
            throw new IllegalStateException("clientOrderId 与原订单意图冲突");
        }
    }

    private long parseOrderId(String value) {
        try {
            long orderId = Long.parseLong(value);
            if (orderId <= 0L) {
                throw new IllegalArgumentException("订单编号必须为正数");
            }
            return orderId;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("订单编号无效", ex);
        }
    }

    private long commandId(long orderId, OrderCommandType type) {
        long code = type == OrderCommandType.PLACE ? 1L : type == OrderCommandType.CANCEL ? 2L : 3L;
        return Math.addExact(orderId, code);
    }

    private long orderEventId(long orderId, OrderEventType type) {
        // 订单编号低两位恒为零，四种订单事件占用其后的四个连续编号；不同订单的编号间隔至少为四，
        // 因此事件编号在本地生成且不会因为类型变化发生碰撞。
        return Math.addExact(orderId, type.ordinal() + 1L);
    }

    public static String reservationCommandId(ProductLine productLine, long orderId) {
        return "ORDER_RESERVE:" + productLine.name() + ":" + orderId;
    }

    private OrderResponse toResponse(OrderRecord order) {
        return new OrderResponse(order.orderId(), order.userId(), order.clientOrderId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(), order.marginMode(),
                order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.reduceOnly(),
                order.postOnly(), order.status(), order.rejectReason(), order.createdAt(), order.updatedAt());
    }
}
