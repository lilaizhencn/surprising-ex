package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PositionUpdatedEvent;
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
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserEvent;
import com.surprising.trading.order.model.OrderUserState;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
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
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private long lastTimestamp;
    private int sequence;

    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, wal, stateStore, lane, kafkaTemplate, null, null, null);
    }

    @Autowired
    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 @Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                 @Nullable OrderIdSequenceStore orderIdSequenceStore,
                                 @Nullable OrderMarginSnapshotCache marginSnapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.stateStore = stateStore;
        this.lane = lane;
        this.kafkaTemplate = kafkaTemplate;
        this.accountStateSnapshotCache = accountStateSnapshotCache;
        this.orderIdSequenceStore = orderIdSequenceStore;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    /** 兼容单元测试构造方式；生产环境由 Spring 注入全部本地快照组件。 */
    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 @Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                 @Nullable OrderIdSequenceStore orderIdSequenceStore) {
        this(objectMapper, properties, wal, stateStore, lane, kafkaTemplate,
                accountStateSnapshotCache, orderIdSequenceStore, null);
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
            applyPartition(partition);
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
            applyPartition(partition);
            return toResponse(find(state(partition), order.orderId()));
        });
    }

    /** 同一用户分区内原子检查保证金模式，避免两个并发下单在检查与写入之间交错。 */
    public boolean hasActiveMarginModeConflict(long userId, String symbol, com.surprising.trading.api.model.MarginMode marginMode) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            applyPartition(partition);
            return state(partition).orders().stream()
                    .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                    .filter(value -> value.status() != OrderStatus.CANCELED
                            && value.status() != OrderStatus.REJECTED
                            && value.status() != OrderStatus.FILLED)
                    .anyMatch(value -> value.marginMode() != com.surprising.trading.api.model.MarginMode.defaultIfNull(marginMode));
        });
    }

    /** 算法单与普通订单共用同一用户 WAL 和单写入 lane。 */
    public AlgoOrderResponse placeAlgo(AlgoOrderRecord order) {
        UserPartitionKey partition = partition(order.productLine(), order.userId());
        return lane.execute(partition, () -> {
            applyPartition(partition);
            OrderUserState current = state(partition);
            Optional<AlgoOrderRecord> duplicate = current.algoOrders().stream()
                    .filter(value -> value.clientAlgoOrderId() != null
                            && value.clientAlgoOrderId().equals(order.clientAlgoOrderId()))
                    .findFirst();
            if (duplicate.isPresent()) {
                requireSameAlgoIntent(duplicate.get(), order);
                return toAlgoResponse(current, duplicate.get());
            }
            append(partition, OrderUserEvent.algoPlace(order));
            applyPartition(partition);
            return toAlgoResponse(state(partition), findAlgo(state(partition), order.algoOrderId()));
        });
    }

    public AlgoOrderRecord algo(long userId, long algoOrderId) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            applyPartition(partition);
            return findAlgo(state(partition), algoOrderId);
        });
    }

    public AlgoOrderRecord algoById(long algoOrderId) {
        for (UserPartitionKey partition : orderedPartitions(properties.getKafka().getProductLine())) {
            Optional<AlgoOrderRecord> found = lane.execute(partition, () -> {
                applyPartition(partition);
                return state(partition).algoOrders().stream()
                        .filter(value -> value.algoOrderId() == algoOrderId).findFirst();
            });
            if (found.isPresent()) {
                return found.orElseThrow();
            }
        }
        throw new IllegalStateException("算法单不存在: " + algoOrderId);
    }

    public AlgoOrderResponse algoResponse(AlgoOrderRecord order) {
        UserPartitionKey partition = partition(order.productLine(), order.userId());
        return lane.execute(partition, () -> {
            applyPartition(partition);
            return toAlgoResponse(state(partition), findAlgo(state(partition), order.algoOrderId()));
        });
    }

    public void updateAlgo(AlgoOrderRecord order) {
        UserPartitionKey partition = partition(order.productLine(), order.userId());
        lane.execute(partition, () -> {
            applyPartition(partition);
            findAlgo(state(partition), order.algoOrderId());
            append(partition, OrderUserEvent.algoUpdate(order));
            applyPartition(partition);
            return null;
        });
    }

    public void linkAlgoChild(AlgoOrderRecord order, AlgoOrderChild child) {
        if (order.algoOrderId() != child.algoOrderId()) {
            throw new IllegalArgumentException("算法单切片编号不匹配");
        }
        UserPartitionKey partition = partition(order.productLine(), order.userId());
        lane.execute(partition, () -> {
            applyPartition(partition);
            findAlgo(state(partition), order.algoOrderId());
            append(partition, OrderUserEvent.algoChild(order, child));
            applyPartition(partition);
            return null;
        });
    }

    public AlgoOrderProgress algoProgress(long userId, long algoOrderId) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> progress(stateAfterApply(partition), algoOrderId));
    }

    public List<AlgoOrderChild> algoChildren(long userId, long algoOrderId) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> stateAfterApply(partition).algoChildren().stream()
                .filter(value -> value.algoOrderId() == algoOrderId)
                .sorted(java.util.Comparator.comparingInt(AlgoOrderChild::sliceIndex))
                .toList());
    }

    public List<AlgoOrderRecord> claimDueAlgos(ProductLine productLine, Instant now, int limit, java.time.Duration lease) {
        if (productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("算法单产品线与当前订单节点不一致");
        }
        List<AlgoOrderRecord> claimed = new ArrayList<>();
        for (UserPartitionKey partition : orderedPartitions(productLine)) {
            if (claimed.size() >= limit) {
                break;
            }
            int remaining = limit - claimed.size();
            claimed.addAll(lane.execute(partition, () -> {
                OrderUserState current = stateAfterApply(partition);
                List<AlgoOrderRecord> due = current.algoOrders().stream()
                        .filter(value -> (value.status() == com.surprising.trading.api.model.AlgoOrderStatus.PENDING
                                || value.status() == com.surprising.trading.api.model.AlgoOrderStatus.RUNNING)
                                && value.nextSliceAt() != null && !value.nextSliceAt().isAfter(now))
                        .sorted(java.util.Comparator.comparing(AlgoOrderRecord::nextSliceAt)
                                .thenComparingLong(AlgoOrderRecord::algoOrderId))
                        .limit(remaining)
                        .toList();
                List<AlgoOrderRecord> result = new ArrayList<>(due.size());
                for (AlgoOrderRecord order : due) {
                    AlgoOrderRecord claimedOrder = withAlgoSchedule(order,
                            com.surprising.trading.api.model.AlgoOrderStatus.RUNNING,
                            now.plus(lease), now);
                    append(partition, OrderUserEvent.algoUpdate(claimedOrder));
                    result.add(claimedOrder);
                }
                if (!due.isEmpty()) {
                    applyPartition(partition);
                }
                return result;
            }));
        }
        return List.copyOf(claimed);
    }

    public List<AlgoOrderRecord> scheduledAlgos(ProductLine productLine, long afterAlgoOrderId, int limit) {
        if (productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("算法单产品线与当前订单节点不一致");
        }
        return orderedPartitions(productLine).stream()
                .flatMap(partition -> lane.execute(partition, () -> stateAfterApply(partition).algoOrders().stream()
                        .filter(value -> value.algoOrderId() > afterAlgoOrderId
                                && (value.status() == com.surprising.trading.api.model.AlgoOrderStatus.PENDING
                                || value.status() == com.surprising.trading.api.model.AlgoOrderStatus.RUNNING)
                                && value.nextSliceAt() != null)
                        .toList()).stream())
                .sorted(java.util.Comparator.comparingLong(AlgoOrderRecord::algoOrderId))
                .limit(Math.max(1, limit))
                .toList();
    }

    public List<AlgoOrderResponse> openAlgos(long userId, String symbol, int limit) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        return lane.execute(partition, () -> {
            OrderUserState current = stateAfterApply(partition);
            return current.algoOrders().stream()
                    .filter(value -> normalizedSymbol == null || value.symbol().equals(normalizedSymbol))
                    .filter(value -> !isAlgoTerminal(value.status()))
                    .sorted(java.util.Comparator.comparing(AlgoOrderRecord::createdAt).reversed())
                    .limit(limit)
                    .map(value -> toAlgoResponse(current, value))
                    .toList();
        });
    }

    public List<AlgoOrderRecord> lifecycleAlgos(ProductLine productLine, String symbol, int limit) {
        return orderedPartitions(productLine).stream()
                .flatMap(partition -> lane.execute(partition, () -> stateAfterApply(partition).algoOrders().stream()
                        .filter(value -> value.symbol().equalsIgnoreCase(symbol) && !isAlgoTerminal(value.status()))
                        .sorted(java.util.Comparator.comparing(AlgoOrderRecord::createdAt))
                        .limit(limit)
                        .toList()).stream())
                .limit(Math.max(1, limit))
                .toList();
    }

    public boolean hasLifecycleActiveAlgos(ProductLine productLine, String symbol) {
        return !lifecycleAlgos(productLine, symbol, 1).isEmpty();
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
            applyPartition(partition);
            OrderUserState current = state(partition);
            OrderRecord order = find(current, orderId);
            if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.FILLED
                    || order.status() == OrderStatus.REJECTED || order.status() == OrderStatus.CANCEL_REQUESTED) {
                return toResponse(order);
            }
            append(partition, OrderUserEvent.cancel(orderId, reason));
            applyPartition(partition);
            return toResponse(find(state(partition), orderId));
        });
    }

    /**
     * 管理撤单也必须定位到订单所属用户分区后追加撤单事实，不能回查订单表再开启数据库事务。
     */
    public OrderResponse cancelAny(ProductLine productLine, long orderId, String reason) {
        if (productLine == null || productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单产品线与当前订单节点不一致");
        }
        for (UserPartitionKey partition : orderedPartitions(productLine)) {
            Optional<OrderRecord> candidate = lane.execute(partition, () -> {
                applyPartition(partition);
                return state(partition).orders().stream()
                    .filter(value -> value.orderId() == orderId)
                    .findFirst();
            });
            if (candidate.isEmpty()) {
                continue;
            }
            return lane.execute(partition, () -> {
                applyPartition(partition);
                OrderRecord order = find(state(partition), orderId);
                if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.FILLED
                        || order.status() == OrderStatus.REJECTED || order.status() == OrderStatus.CANCEL_REQUESTED) {
                    return toResponse(order);
                }
                append(partition, OrderUserEvent.cancel(orderId, reason));
                applyPartition(partition);
                return toResponse(find(state(partition), orderId));
            });
        }
        throw new IllegalStateException("订单不存在: " + orderId);
    }

    /**
     * 按用户分区扫描管理撤单范围；扫描和追加事实在同一个分区 lane 中完成，避免读写交错。
     */
    public List<OrderResponse> cancelAdminOrders(ProductLine productLine,
                                                  Long userId,
                                                  String symbol,
                                                  int limit,
                                                  String reason) {
        if (productLine == null || productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单产品线与当前订单节点不一致");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        List<OrderResponse> result = new ArrayList<>();
        for (UserPartitionKey partition : orderedPartitions(productLine)) {
            if (result.size() >= limit) {
                break;
            }
            int remaining = limit - result.size();
            List<OrderResponse> canceled = lane.execute(partition, () -> {
                applyPartition(partition);
                OrderUserState current = state(partition);
                List<OrderRecord> orders = current.orders().stream()
                        .filter(value -> userId == null || value.userId() == userId)
                        .filter(value -> normalizedSymbol == null || value.symbol().equals(normalizedSymbol))
                        .filter(value -> value.status() != OrderStatus.CANCELED
                                && value.status() != OrderStatus.REJECTED
                                && value.status() != OrderStatus.FILLED
                                && value.status() != OrderStatus.CANCEL_REQUESTED)
                        .sorted(java.util.Comparator.comparingLong(OrderRecord::orderId))
                        .limit(remaining)
                        .toList();
                List<OrderResponse> responses = new ArrayList<>(orders.size());
                for (OrderRecord order : orders) {
                    append(partition, OrderUserEvent.cancel(order.orderId(), reason));
                }
                applyPartition(partition);
                for (OrderRecord order : orders) {
                    responses.add(toResponse(find(state(partition), order.orderId())));
                }
                return List.copyOf(responses);
            });
            result.addAll(canceled);
        }
        return List.copyOf(result);
    }

    /**
     * 生命周期清理只操作本地订单状态；数据库订单表仅作为异步投影，不参与撤单裁决。
     */
    public List<OrderResponse> cancelLifecycleOrders(ProductLine productLine,
                                                      String symbol,
                                                      int limit,
                                                      String reason) {
        return cancelAdminOrders(productLine, null, symbol, limit, reason);
    }

    public boolean hasLifecycleActiveOrders(ProductLine productLine, String symbol) {
        if (productLine == null || productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单产品线与当前订单节点不一致");
        }
        String normalizedSymbol = symbol == null ? null : symbol.trim().toUpperCase();
        return orderedPartitions(productLine).stream().anyMatch(partition -> lane.execute(partition, () -> {
            applyPartition(partition);
            return state(partition).orders().stream()
                    .anyMatch(value -> (normalizedSymbol == null || value.symbol().equals(normalizedSymbol))
                            && value.status() != OrderStatus.CANCELED
                            && value.status() != OrderStatus.REJECTED
                            && value.status() != OrderStatus.FILLED);
        }));
    }

    /**
     * 账户持仓快照变小时，按同一用户分区裁剪超出持仓容量的只减仓单。
     * 相同事件重复到达时，固定的 CANCEL 事件编号由 WAL 幂等去重。
     */
    public int pruneReduceOnlyOrders(PositionUpdatedEvent event, String reason) {
        if (event == null || event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("持仓事件产品线与当前订单节点不一致");
        }
        UserPartitionKey partition = partition(event.productLine(), event.userId());
        return lane.execute(partition, () -> {
            applyPartition(partition);
            OrderUserState current = state(partition);
            List<OrderRecord> orders = current.orders().stream()
                    .filter(value -> value.symbol().equalsIgnoreCase(event.symbol()))
                    .filter(value -> value.reduceOnly())
                    .filter(value -> value.status() != OrderStatus.CANCELED
                            && value.status() != OrderStatus.REJECTED
                            && value.status() != OrderStatus.FILLED
                            && value.status() != OrderStatus.CANCEL_REQUESTED)
                    .sorted(java.util.Comparator.comparingLong(OrderRecord::orderId))
                    .toList();
            long capacity = Math.absExact(event.signedQuantitySteps());
            OrderSide closeSide = event.signedQuantitySteps() > 0L
                    ? OrderSide.SELL : event.signedQuantitySteps() < 0L ? OrderSide.BUY : null;
            long consumed = 0L;
            int requested = 0;
            for (OrderRecord order : orders) {
                boolean valid = closeSide != null && order.side() == closeSide
                        && order.instrumentVersion() == event.instrumentVersion()
                        && order.positionSide() == event.positionSide();
                if (valid) {
                    consumed = Math.addExact(consumed, order.remainingQuantitySteps());
                }
                if (!valid || consumed > capacity) {
                    append(partition, OrderUserEvent.cancel(order.orderId(), reason));
                    requested++;
                }
            }
            applyPartition(partition);
            return requested;
        });
    }

    public OrderResponse get(long userId, long orderId) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            applyPartition(partition);
            return toResponse(find(state(partition), orderId));
        });
    }

    public OrderResponse get(long orderId) {
        for (UserPartitionKey partition : orderedPartitions(properties.getKafka().getProductLine())) {
            Optional<OrderRecord> found = lane.execute(partition, () -> {
                applyPartition(partition);
                return state(partition).orders().stream()
                        .filter(value -> value.orderId() == orderId).findFirst();
            });
            if (found.isPresent()) {
                return toResponse(found.orElseThrow());
            }
        }
        throw new IllegalStateException("订单不存在: " + orderId);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        return findByClientOrderId(userId, clientOrderId)
                .orElseThrow(() -> new IllegalStateException("order not found for clientOrderId: " + clientOrderId));
    }

    /**
     * 读取用户分区中的公开幂等键，不把“未找到”与事实流损坏、分区不可用混为一谈。
     */
    public Optional<OrderResponse> findByClientOrderId(long userId, String clientOrderId) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            applyPartition(partition);
            return state(partition).orders().stream()
                    .filter(value -> clientOrderId.equals(value.clientOrderId()))
                    .findFirst().map(this::toResponse);
        });
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, long beforeOrderId) {
        String normalized = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        List<OrderResponse> orders = lane.execute(partition, () -> {
            applyPartition(partition);
            return state(partition).orders().stream()
                .filter(value -> value.orderId() < beforeOrderId || beforeOrderId <= 0L)
                .filter(value -> normalized == null || value.symbol().equals(normalized))
                .filter(value -> value.status() != OrderStatus.CANCELED && value.status() != OrderStatus.REJECTED
                        && value.status() != OrderStatus.FILLED)
                .sorted(java.util.Comparator.comparingLong(OrderRecord::orderId).reversed())
                .limit(limit + 1L)
                .map(this::toResponse)
                .toList();
        });
        boolean more = orders.size() > limit;
        List<OrderResponse> page = more ? orders.subList(0, limit) : orders;
        String cursor = more ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("order:" + page.getLast().orderId()).getBytes(StandardCharsets.UTF_8)) : null;
        return new OrderQueryResponse(page.size(), page, cursor, more, "orderId.desc", limit);
    }

    /** 返回当前产品线所有用户分区中的本地订单状态，供 JVM 快照启动重建使用。 */
    public List<OrderRecord> localOrders(ProductLine productLine) {
        requireCurrentProductLine(productLine);
        return orderedPartitions(productLine).stream()
                .flatMap(partition -> lane.execute(partition, () -> stateAfterApply(partition).orders().stream()))
                .toList();
    }

    /** 管理查询也只扫描用户分区快照，数据库订单投影不参与在线裁决。 */
    public OrderQueryResponse adminOrders(ProductLine productLine,
                                           Long userId,
                                           String symbol,
                                           OrderStatus status,
                                           Long orderId,
                                           int limit,
                                           String cursor,
                                           String sort) {
        requireCurrentProductLine(productLine);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        long beforeOrderId = OrderService.decodeOpenOrderCursor(cursor);
        boolean ascending = sort != null && sort.toLowerCase(java.util.Locale.ROOT).contains("asc");
        List<OrderResponse> rows = orderedPartitions(productLine).stream()
                .flatMap(partition -> lane.execute(partition, () -> stateAfterApply(partition).orders().stream()
                        .filter(order -> userId == null || order.userId() == userId)
                        .filter(order -> normalizedSymbol == null || order.symbol().equals(normalizedSymbol))
                        .filter(order -> status == null || order.status() == status)
                        .filter(order -> orderId == null || order.orderId() == orderId)
                        .filter(order -> ascending ? order.orderId() > beforeOrderId
                                : order.orderId() < beforeOrderId)
                        .map(this::toResponse)
                        .toList()).stream())
                .sorted((left, right) -> ascending
                        ? Long.compare(left.orderId(), right.orderId())
                        : Long.compare(right.orderId(), left.orderId()))
                .limit((long) limit + 1L)
                .toList();
        boolean more = rows.size() > limit;
        List<OrderResponse> page = more ? rows.subList(0, limit) : rows;
        String nextCursor = more && !page.isEmpty()
                ? OrderService.encodeOpenOrderCursor(page.getLast().orderId()) : null;
        return new OrderQueryResponse(page.size(), page, nextCursor, more,
                ascending ? "orderId.asc" : "orderId.desc", limit);
    }

    /** 管理撤单预览只读取本地快照，预览与实际撤单使用同一份状态来源。 */
    public AdminCancelOrdersPreviewResponse adminCancelPreview(ProductLine productLine,
                                                                Long userId,
                                                                String symbol,
                                                                int limit) {
        requireCurrentProductLine(productLine);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        List<OrderResponse> candidates = orderedPartitions(productLine).stream()
                .flatMap(partition -> lane.execute(partition, () -> stateAfterApply(partition).orders().stream()
                        .filter(order -> userId == null || order.userId() == userId)
                        .filter(order -> normalizedSymbol == null || order.symbol().equals(normalizedSymbol))
                        .filter(order -> order.status() != OrderStatus.CANCELED
                                && order.status() != OrderStatus.REJECTED
                                && order.status() != OrderStatus.FILLED
                                && order.status() != OrderStatus.CANCEL_REQUESTED)
                        .map(this::toResponse)
                        .toList()).stream())
                .sorted(java.util.Comparator.comparingLong(OrderResponse::orderId))
                .toList();
        List<OrderResponse> sample = candidates.stream().limit(limit).toList();
        long totalRemaining = candidates.stream().mapToLong(OrderResponse::remainingQuantitySteps).sum();
        int buyOrders = Math.toIntExact(candidates.stream().filter(order -> order.side() == OrderSide.BUY).count());
        int sellOrders = Math.toIntExact(candidates.stream().filter(order -> order.side() == OrderSide.SELL).count());
        return new AdminCancelOrdersPreviewResponse(userId, normalizedSymbol, candidates.size(), sample.size(),
                totalRemaining, buyOrders, sellOrders, sample);
    }

    private void requireCurrentProductLine(ProductLine productLine) {
        if (productLine == null || productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单产品线与当前订单节点不一致");
        }
    }

    public List<OrderResponse> cancelOpenOrders(long userId, String symbol, int limit) {
        UserPartitionKey partition = partition(properties.getKafka().getProductLine(), userId);
        return lane.execute(partition, () -> {
            applyPartition(partition);
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
            }
            applyPartition(partition);
            for (OrderRecord order : orders) {
                responses.add(toResponse(find(state(partition), order.orderId())));
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
                applyPartition(partition);
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
            applyPartition(partition);
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
            current = new OrderUserState(current.orders(), eventIds, current.algoOrders(), current.algoChildren());
            stateStore.apply(partition, raw.sequence(), serialize(current));
            if (marginSnapshotCache != null) {
                for (OrderRecord order : current.orders()) {
                    OrderMarginSnapshotCache.ApplyResult result = marginSnapshotCache.applyOrder(order);
                    if (result == OrderMarginSnapshotCache.ApplyResult.CONFLICT) {
                        marginSnapshotCache.markNotReady(partition.productLine());
                        throw new IllegalStateException("订单 JVM 快照同一修订号出现不同状态");
                    }
                }
            }
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
            case "ALGO_PLACE" -> applyAlgoPlace(current, event.algoOrder());
            case "ALGO_CHILD" -> applyAlgoChild(current, event.algoOrder(), event.algoChild());
            default -> event.eventType().startsWith("ALGO_UPDATE:")
                    ? applyAlgoUpdate(current, event.algoOrder()) : unknownEvent(event);
        };
    }

    private OrderUserState applyPlace(OrderUserState current, OrderRecord order) {
        if (current.orders().stream().anyMatch(value -> value.orderId() == order.orderId())) {
            return current;
        }
        publishForPlace(order);
        List<OrderRecord> orders = new ArrayList<>(current.orders());
        orders.add(order);
        return new OrderUserState(orders, current.appliedEventIds(), current.algoOrders(), current.algoChildren());
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
        return new OrderUserState(updatedOrders, current.appliedEventIds(), current.algoOrders(), current.algoChildren());
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

    private OrderUserState applyAlgoPlace(OrderUserState current, AlgoOrderRecord order) {
        if (order == null) {
            throw new IllegalStateException("算法单事实缺少订单");
        }
        if (current.algoOrders().stream().anyMatch(value -> value.algoOrderId() == order.algoOrderId())) {
            return current;
        }
        List<AlgoOrderRecord> orders = new ArrayList<>(current.algoOrders());
        orders.add(order);
        return new OrderUserState(current.orders(), current.appliedEventIds(), orders, current.algoChildren());
    }

    private OrderUserState applyAlgoUpdate(OrderUserState current, AlgoOrderRecord updated) {
        if (updated == null) {
            throw new IllegalStateException("算法单更新事实缺少订单");
        }
        findAlgo(current, updated.algoOrderId());
        List<AlgoOrderRecord> orders = current.algoOrders().stream()
                .map(value -> value.algoOrderId() == updated.algoOrderId() ? updated : value)
                .toList();
        return new OrderUserState(current.orders(), current.appliedEventIds(), orders, current.algoChildren());
    }

    private OrderUserState applyAlgoChild(OrderUserState current,
                                          AlgoOrderRecord updated,
                                          AlgoOrderChild child) {
        if (updated == null || child == null || updated.algoOrderId() != child.algoOrderId()) {
            throw new IllegalStateException("算法单切片事实不完整");
        }
        OrderUserState withOrder = applyAlgoUpdate(current, updated);
        if (withOrder.algoChildren().stream().anyMatch(value -> value.algoOrderId() == child.algoOrderId()
                && value.sliceIndex() == child.sliceIndex())) {
            return withOrder;
        }
        List<AlgoOrderChild> children = new ArrayList<>(withOrder.algoChildren());
        children.add(child);
        return new OrderUserState(withOrder.orders(), withOrder.appliedEventIds(), withOrder.algoOrders(), children);
    }

    private OrderUserState stateAfterApply(UserPartitionKey partition) {
        applyPartition(partition);
        return state(partition);
    }

    private AlgoOrderRecord findAlgo(OrderUserState state, long algoOrderId) {
        return state.algoOrders().stream()
                .filter(value -> value.algoOrderId() == algoOrderId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("算法单不存在: " + algoOrderId));
    }

    private AlgoOrderProgress progress(OrderUserState state, long algoOrderId) {
        List<AlgoOrderChild> children = state.algoChildren().stream()
                .filter(value -> value.algoOrderId() == algoOrderId)
                .toList();
        long executed = 0L;
        long active = 0L;
        int activeCount = 0;
        int nextSlice = 0;
        for (AlgoOrderChild child : children) {
            nextSlice = Math.max(nextSlice, child.sliceIndex() + 1);
            OrderRecord order = state.orders().stream()
                    .filter(value -> value.orderId() == child.orderId())
                    .findFirst()
                    .orElse(null);
            if (order == null) {
                throw new IllegalStateException("算法子单不在用户订单状态中: " + child.orderId());
            }
            executed = Math.addExact(executed, order.executedQuantitySteps());
            if ((order.status() == OrderStatus.ACCEPTED || order.status() == OrderStatus.PARTIALLY_FILLED
                    || order.status() == OrderStatus.CANCEL_REQUESTED) && order.remainingQuantitySteps() > 0L) {
                active = Math.addExact(active, order.remainingQuantitySteps());
                activeCount++;
            }
        }
        return new AlgoOrderProgress(executed, active, children.size(), activeCount, nextSlice);
    }

    private AlgoOrderResponse toAlgoResponse(OrderUserState state, AlgoOrderRecord order) {
        AlgoOrderProgress progress = progress(state, order.algoOrderId());
        return new AlgoOrderResponse(order.algoOrderId(), order.userId(), order.clientAlgoOrderId(), order.symbol(),
                order.algoType(), order.side(), order.priceTicks(), order.quantitySteps(), order.childQuantitySteps(),
                order.intervalSeconds(), order.durationSeconds(), order.marginMode(), order.positionSide(),
                order.reduceOnly(), order.postOnly(), order.timeInForce(), order.status(), progress.executedQuantitySteps(),
                progress.activeQuantitySteps(), progress.childOrderCount(), order.currentOrderId(), order.rejectReason(),
                order.startAt(), order.nextSliceAt(), order.completedAt(), order.createdAt(), order.updatedAt());
    }

    private AlgoOrderRecord withAlgoSchedule(AlgoOrderRecord order,
                                             com.surprising.trading.api.model.AlgoOrderStatus status,
                                             Instant nextSliceAt,
                                             Instant now) {
        return new AlgoOrderRecord(order.algoOrderId(), order.productLine(), order.userId(),
                order.clientAlgoOrderId(), order.symbol(), order.algoType(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.childQuantitySteps(), order.intervalSeconds(), order.durationSeconds(),
                order.marginMode(), order.positionSide(), order.reduceOnly(), order.postOnly(), order.timeInForce(),
                status, order.currentOrderId(), order.rejectReason(), order.traceId(), order.startAt(), nextSliceAt,
                order.completedAt(), order.createdAt(), now);
    }

    private boolean isAlgoTerminal(com.surprising.trading.api.model.AlgoOrderStatus status) {
        return status == com.surprising.trading.api.model.AlgoOrderStatus.CANCELED
                || status == com.surprising.trading.api.model.AlgoOrderStatus.COMPLETED
                || status == com.surprising.trading.api.model.AlgoOrderStatus.FAILED;
    }

    private void requireSameAlgoIntent(AlgoOrderRecord left, AlgoOrderRecord right) {
        if (left.userId() != right.userId() || left.productLine() != right.productLine()
                || !java.util.Objects.equals(left.clientAlgoOrderId(), right.clientAlgoOrderId())
                || !java.util.Objects.equals(left.symbol(), right.symbol()) || left.algoType() != right.algoType()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps() || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce()) {
            throw new IllegalArgumentException("clientAlgoOrderId already used with different algo parameters");
        }
    }

    private OrderUserState unknownEvent(OrderUserEvent event) {
        throw new IllegalStateException("未知订单事实事件: " + event.eventType());
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

    private List<UserPartitionKey> orderedPartitions(ProductLine productLine) {
        java.util.Set<UserPartitionKey> partitions = new java.util.HashSet<>(stateStore.partitions());
        partitions.addAll(wal.partitions());
        return partitions.stream()
                .filter(value -> value.productLine() == productLine)
                .sorted(java.util.Comparator.comparing(UserPartitionKey::value))
                .toList();
    }

    private OrderRecord find(OrderUserState state, long orderId) {
        return state.orders().stream().filter(value -> value.orderId() == orderId).findFirst()
                .orElseThrow(() -> new IllegalStateException("订单不存在: " + orderId));
    }

    private OrderUserState replace(OrderUserState state, OrderRecord updated) {
        return new OrderUserState(state.orders().stream().map(value -> value.orderId() == updated.orderId()
                ? updated : value).toList(), state.appliedEventIds(), state.algoOrders(), state.algoChildren());
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
