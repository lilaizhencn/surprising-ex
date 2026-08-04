package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionResultStore;
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
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.api.model.OrderUserCommandStatus;
import com.surprising.trading.api.model.OrderUserCommandType;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserEvent;
import com.surprising.trading.order.model.OrderUserState;
import com.surprising.trading.order.model.OrderUserStateSnapshot;
import com.surprising.trading.order.model.OrderUserAlgoChildCommand;
import com.surprising.trading.order.model.OrderUserCancelCommand;
import com.surprising.trading.order.model.OrderUserCancelOpenCommand;
import com.surprising.trading.order.model.OrderUserPruneReduceOnlyCommand;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单用户分区的单写者状态机。
 *
 * <p>下单、账户预占结果、撤单和撮合结果都先进入同一个用户 WAL，再由本地状态快照按序应用。
 * Kafka 用户命令 Topic 负责跨节点分区路由，数据库不参与订单状态裁决。外部重复消息由
 * WAL 事件编号、结果库和状态机的 appliedEventIds 三重幂等。</p>
 */
@Service
public class OrderUserStateService {

    private static final Logger log = LoggerFactory.getLogger(OrderUserStateService.class);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionResultStore resultStore;
    private final UserPartitionCommandLane lane;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;
    private final OrderIdSequenceStore orderIdSequenceStore;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final Map<UserPartitionKey, Long> publishedSnapshotRevisions = new ConcurrentHashMap<>();
    private final AtomicReference<LocalSequence> localSequence = new AtomicReference<>(new LocalSequence(0L, -1));

    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, wal, stateStore, lane, kafkaTemplate, null, null, null, null);
    }

    @Autowired
    public OrderUserStateService(ObjectMapper objectMapper,
                                 TradingOrderProperties properties,
                                 UserPartitionWal wal,
                                 UserPartitionStateStore stateStore,
                                 UserPartitionCommandLane lane,
                                 @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                 @Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                 @Nullable OrderIdSequenceStore orderIdSequenceStore,
                                 @Nullable OrderMarginSnapshotCache marginSnapshotCache,
                                 @Nullable UserPartitionResultStore resultStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.stateStore = stateStore;
        this.resultStore = resultStore;
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
                accountStateSnapshotCache, orderIdSequenceStore, null, null);
    }

    /** 订单编号不依赖数据库序列；低两位预留给同一订单的命令编号。 */
    public long nextOrderId() {
        if (orderIdSequenceStore != null) {
            return orderIdSequenceStore.next();
        }
        while (true) {
            LocalSequence previous = localSequence.get();
            long now = System.currentTimeMillis();
            if (now < previous.timestamp()) {
                now = previous.timestamp();
            }
            int current;
            if (now == previous.timestamp()) {
                if (previous.sequence() >= 1_023) {
                    now = Math.addExact(previous.timestamp(), 1L);
                    current = 0;
                } else {
                    current = previous.sequence() + 1;
                }
            } else {
                current = 0;
            }
            long value = Math.addExact(Math.multiplyExact(now, 1L << 22),
                    Math.addExact(((long) properties.getWal().getNodeId()) << 12, ((long) current) << 2));
            if (value <= 0L) {
                throw new IllegalStateException("订单编号溢出");
            }
            if (localSequence.compareAndSet(previous, new LocalSequence(now, current))) {
                return value;
            }
        }
    }

    /**
     * 用户命令消费者的本地执行入口。
     *
     * <p>只有该入口可以把跨节点命令转换成当前用户 WAL 事实。结果先保存到本地结果库，再
     * 由消费者发布到结果 Topic；相同命令重放时直接返回原终态，不会再次冻结资金或推进订单。</p>
     */
    public OrderUserCommandResult executeUserCommand(OrderUserCommand command) {
        if (command == null || command.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单用户命令产品线不匹配");
        }
        UserPartitionKey partition = new UserPartitionKey(command.productLine(), command.userId());
        if (resultStore == null) {
            throw new IllegalStateException("订单用户命令结果库尚未配置");
        }
        return lane.execute(partition, () -> {
            OrderUserCommandResult existing = readCommandResult(partition, command.commandId()).orElse(null);
            if (existing != null) {
                if (!commandFingerprint(command).equals(existing.commandFingerprint())) {
                    throw new IllegalStateException("订单用户命令编号与载荷指纹冲突: " + command.commandId());
                }
                return existing;
            }
            String payload;
            switch (command.commandType()) {
                case PLACE -> {
                    OrderRecord order = readPayload(command.payload(), OrderRecord.class);
                    requireCommandIdentity(command, order.productLine(), order.userId());
                    payload = objectMapper.writeValueAsString(place(order));
                }
                case CANCEL -> {
                    OrderUserCancelCommand cancel = readPayload(command.payload(), OrderUserCancelCommand.class);
                    payload = objectMapper.writeValueAsString(cancel(command.userId(),
                            cancel.orderId(), cancel.reason()));
                }
                case CANCEL_OPEN -> {
                    OrderUserCancelOpenCommand cancel = readPayload(command.payload(), OrderUserCancelOpenCommand.class);
                    List<OrderResponse> canceled = cancelOpenOrders(command.userId(), cancel.symbol(), cancel.limit(),
                            cancel.reason());
                    List<OrderBatchItemResponse> items = new ArrayList<>(canceled.size());
                    for (int index = 0; index < canceled.size(); index++) {
                        items.add(new OrderBatchItemResponse(index, true, "cancel requested", canceled.get(index)));
                    }
                    payload = objectMapper.writeValueAsString(new OrderBatchResponse(items.size(), items.size(), 0,
                            items));
                }
                case PRUNE_REDUCE_ONLY -> {
                    OrderUserPruneReduceOnlyCommand prune =
                            readPayload(command.payload(), OrderUserPruneReduceOnlyCommand.class);
                    requireCommandIdentity(command, prune.position().productLine(), prune.position().userId());
                    int requested = pruneReduceOnlyOrders(prune.position(), prune.reason());
                    payload = objectMapper.writeValueAsString(java.util.Map.of("requested", requested));
                }
                case ALGO_PLACE -> {
                    AlgoOrderRecord order = readPayload(command.payload(), AlgoOrderRecord.class);
                    requireCommandIdentity(command, order.productLine(), order.userId());
                    payload = objectMapper.writeValueAsString(placeAlgo(order));
                }
                case ALGO_UPDATE -> {
                    AlgoOrderRecord order = readPayload(command.payload(), AlgoOrderRecord.class);
                    requireCommandIdentity(command, order.productLine(), order.userId());
                    updateAlgo(order);
                    payload = objectMapper.writeValueAsString(algoResponse(order));
                }
                case ALGO_CHILD -> {
                    OrderUserAlgoChildCommand child = readPayload(command.payload(), OrderUserAlgoChildCommand.class);
                    requireCommandIdentity(command, child.order().productLine(), child.order().userId());
                    linkAlgoChild(child.order(), child.child());
                    payload = objectMapper.writeValueAsString(algoResponse(child.order()));
                }
                case ACCOUNT_RESULT -> {
                    AccountCommandResultEvent result = readPayload(command.payload(), AccountCommandResultEvent.class);
                    requireCommandIdentity(command, result.productLine(), result.userId());
                    processAccountCommandResultForUser(result);
                    payload = objectMapper.writeValueAsString(java.util.Map.of("applied", true));
                }
                case MATCH_RESULT -> {
                    MatchResultEvent result = readPayload(command.payload(), MatchResultEvent.class);
                    processMatchResultForUser(command.userId(), result);
                    payload = objectMapper.writeValueAsString(java.util.Map.of("applied", true));
                }
                default -> throw new IllegalStateException("未知订单用户命令: " + command.commandType());
            }
            OrderUserCommandResult terminal = new OrderUserCommandResult(
                    OrderUserCommandResult.CURRENT_SCHEMA_VERSION, command.commandId(), command.productLine(),
                    command.userId(), command.commandType(), commandFingerprint(command),
                    OrderUserCommandStatus.APPLIED, payload,
                    null, null, Instant.now(), command.traceId());
            resultStore.put(partition, command.commandId(), serialize(terminal));
            return terminal;
        });
    }

    private <T> T readPayload(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("订单用户命令载荷无法解析: " + type.getSimpleName(), ex);
        }
    }

    private void requireCommandIdentity(OrderUserCommand command, ProductLine productLine, long userId) {
        if (productLine != command.productLine() || userId != command.userId()) {
            throw new IllegalArgumentException("订单用户命令载荷分区与命令不一致");
        }
    }

    private Optional<OrderUserCommandResult> readCommandResult(UserPartitionKey partition, String commandId) {
        return resultStore.read(partition, commandId).map(bytes -> readPayload(
                new String(bytes, StandardCharsets.UTF_8), OrderUserCommandResult.class));
    }

    private String commandFingerprint(OrderUserCommand command) {
        try {
            // occurredAt 和 traceId 只是传输元数据。Kafka 重试、重新封装或跨节点转发时可能变化，
            // 不能把它们作为同一幂等命令的载荷指纹，否则重复撮合结果会被错误地判定为冲突。
            CommandFingerprint fingerprint = new CommandFingerprint(command.schemaVersion(), command.commandId(),
                    command.productLine(), command.userId(), command.commandType(), command.payload());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    serialize(fingerprint)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 订单用户命令参与幂等校验的稳定字段。 */
    private record CommandFingerprint(int schemaVersion,
                                      String commandId,
                                      ProductLine productLine,
                                      long userId,
                                      OrderUserCommandType commandType,
                                      String payload) {
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

    /** 只读返回到期算法单；状态变更必须由算法命令通过用户 Topic 提交。 */
    public List<AlgoOrderRecord> dueAlgos(ProductLine productLine, Instant now, int limit) {
        if (productLine != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("算法单产品线与当前订单节点不一致");
        }
        List<AlgoOrderRecord> result = new ArrayList<>();
        for (UserPartitionKey partition : orderedPartitions(productLine)) {
            if (result.size() >= limit) {
                break;
            }
            int remaining = limit - result.size();
            result.addAll(lane.execute(partition, () -> stateAfterApply(partition).algoOrders().stream()
                    .filter(value -> (value.status() == com.surprising.trading.api.model.AlgoOrderStatus.PENDING
                            || value.status() == com.surprising.trading.api.model.AlgoOrderStatus.RUNNING)
                            && value.nextSliceAt() != null && !value.nextSliceAt().isAfter(now))
                    .sorted(java.util.Comparator.comparing(AlgoOrderRecord::nextSliceAt)
                            .thenComparingLong(AlgoOrderRecord::algoOrderId))
                    .limit(remaining)
                    .toList()));
        }
        return List.copyOf(result);
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
                || !order.productLine().supportsUserPositionMarginFlow()) {
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

    /** 只读地查找当前节点拥有的订单分区；管理写操作随后仍必须回到用户命令 Topic。 */
    public Optional<OrderResponse> findAnyLocal(ProductLine productLine, long orderId) {
        requireCurrentProductLine(productLine);
        return orderedPartitions(productLine).stream()
                .map(partition -> lane.execute(partition, () -> stateAfterApply(partition).orders().stream()
                        .filter(order -> order.orderId() == orderId)
                        .findFirst().map(this::toResponse)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** 返回当前节点本地持有的用户分区，供生命周期任务逐用户投递命令。 */
    public List<Long> localUserIds(ProductLine productLine) {
        requireCurrentProductLine(productLine);
        return orderedPartitions(productLine).stream().map(UserPartitionKey::userId).distinct().toList();
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
        return cancelOpenOrders(userId, symbol, limit, "USER_CANCEL_ALL");
    }

    private List<OrderResponse> cancelOpenOrders(long userId, String symbol, int limit, String reason) {
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
                append(partition, OrderUserEvent.cancel(order.orderId(), reason));
            }
            applyPartition(partition);
            for (OrderRecord order : orders) {
                responses.add(toResponse(find(state(partition), order.orderId())));
            }
            return List.copyOf(responses);
        });
    }

    /** 账户结果已经位于用户命令单写入入口后，只允许在当前分区 lane 中追加一次事实。 */
    public void processAccountCommandResultForUser(AccountCommandResultEvent result) {
        if (result == null) {
            throw new IllegalArgumentException("账户结果不能为空");
        }
        requireCurrentProductLine(result.productLine());
        validateAccountResultIdentity(result);
        UserPartitionKey partition = partition(result.productLine(), result.userId());
        lane.execute(partition, () -> {
            append(partition, OrderUserEvent.accountResult(result));
            applyPartition(partition);
            return null;
        });
    }

    /** 账户结果只能确认订单事实流为该订单生成的那一条预占命令。 */
    private void validateAccountResultIdentity(AccountCommandResultEvent result) {
        String sourceReference = result.sourceReference();
        long orderId;
        try {
            orderId = Long.parseLong(sourceReference);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("账户结果订单编号无效", ex);
        }
        if (orderId <= 0L || !reservationCommandId(result.productLine(), orderId).equals(result.commandId())) {
            throw new IllegalArgumentException("账户结果命令编号与订单预占命令不一致");
        }
    }

    /** 只把撮合结果应用到已经由用户命令 Topic 路由到的目标分区。 */
    public void processMatchResultForUser(long userId, MatchResultEvent result) {
        if (result == null || userId <= 0L) {
            throw new IllegalArgumentException("撮合结果用户分区参数无效");
        }
        validateMatchResult(userId, result);
        appendMatch(userId, result);
    }

    private void appendMatch(long userId, MatchResultEvent result) {
        UserPartitionKey partition = partition(result.symbol(), userId);
        lane.execute(partition, () -> {
            append(partition, OrderUserEvent.matchResult(result));
            applyPartition(partition);
            return null;
        });
    }

    /** Service 入口也必须重复校验 Kafka Consumer 已做的产品线和参与方边界。 */
    private void validateMatchResult(long targetUserId, MatchResultEvent result) {
        requireCurrentProductLine(properties.getKafka().getProductLine());
        if (targetUserId <= 0L || result.commandId() <= 0L || result.orderId() <= 0L || result.userId() <= 0L
                || result.symbol() == null || result.symbol().isBlank() || result.instrumentVersion() <= 0L
                || result.filledQuantitySteps() < 0L || result.orderStatus() == null
                || result.trades() == null) {
            throw new IllegalArgumentException("撮合结果身份或数量无效");
        }
        if (result.filledQuantitySteps() > 0L && result.trades().isEmpty()) {
            throw new IllegalArgumentException("撮合结果包含成交数量但缺少成交事实");
        }
        Set<Long> tradeIds = new HashSet<>();
        boolean targetParticipates = targetUserId == result.userId();
        long tradeQuantity = 0L;
        for (MatchTradeEvent trade : result.trades()) {
            if (trade == null || !result.symbol().equalsIgnoreCase(trade.symbol())
                    || trade.tradeId() <= 0L || trade.commandId() <= 0L
                    || trade.commandId() != result.commandId()
                    || trade.takerOrderId() <= 0L || trade.makerOrderId() <= 0L
                    || trade.takerUserId() <= 0L || trade.makerUserId() <= 0L
                    || trade.takerOrderId() != result.orderId() || trade.takerUserId() != result.userId()
                    || trade.priceTicks() <= 0L || trade.quantitySteps() <= 0L
                    || trade.takerInstrumentVersion() <= 0L || trade.makerInstrumentVersion() <= 0L) {
                throw new IllegalArgumentException("撮合成交参与方或交易对不一致 tradeId=" + trade.tradeId()
                        + ",tradeCommandId=" + trade.commandId() + ",resultCommandId=" + result.commandId()
                        + ",takerOrderId=" + trade.takerOrderId() + ",resultOrderId=" + result.orderId()
                        + ",takerUserId=" + trade.takerUserId() + ",resultUserId=" + result.userId());
            }
            if (!tradeIds.add(trade.tradeId())) {
                throw new IllegalArgumentException("撮合结果包含重复成交编号");
            }
            targetParticipates = targetParticipates || trade.makerUserId() == targetUserId;
            tradeQuantity = Math.addExact(tradeQuantity, trade.quantitySteps());
        }
        if (!targetParticipates) {
            throw new IllegalArgumentException("撮合结果不属于目标用户分区");
        }
        if (tradeQuantity != result.filledQuantitySteps()) {
            throw new IllegalArgumentException("撮合结果成交数量与成交事实不一致");
        }
    }

    public void applyPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                lane.execute(partition, () -> applyPartition(partition));
            } catch (RuntimeException ex) {
                log.warn("订单事实流分区执行失败 partition={}", partition.value(), ex);
            }
        }
    }

    /**
     * 消费压缩订单快照时初始化本地状态。
     *
     * <p>快照只允许覆盖尚未应用本地 WAL 的分区。若本地已经有待处理事实，无法证明外部
     * 快照包含这些事实，宁可保留本地分区停住，也不能用旧状态覆盖订单。</p>
     */
    public void initializeSnapshot(OrderUserStateSnapshot snapshot) {
        if (snapshot == null || snapshot.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("订单完整快照产品线不匹配");
        }
        UserPartitionKey partition = new UserPartitionKey(snapshot.productLine(), snapshot.userId());
        lane.execute(partition, () -> {
            long localWalTail = wal.lastSequence(partition);
            var existing = stateStore.read(partition);
            if (localWalTail > 0L && (existing.isEmpty() || existing.get().sequence() == 0L)) {
                throw new IllegalStateException("订单分区存在本地 WAL 但没有状态，拒绝外部快照覆盖: "
                        + partition.value());
            }
            if (existing.isPresent() && existing.get().sequence() > 0L) {
                return null;
            }
            long currentRevision = existing.map(value -> deserialize(value.state()).revision()).orElse(0L);
            if (currentRevision >= snapshot.stateRevision()) {
                return null;
            }
            stateStore.replaceIfUnapplied(partition, serialize(snapshot.state()));
            publishedSnapshotRevisions.put(partition, snapshot.stateRevision());
            return null;
        });
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
            current = new OrderUserState(current.orders(), eventIds, current.algoOrders(), current.algoChildren(),
                    current.appliedTradeIds(), Math.addExact(current.revision(), 1L));
            stateStore.apply(partition, raw.sequence(), serialize(current));
            publishStateSnapshot(partition, current);
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
        if (current.revision() > 0L) {
            // 状态提交成功而快照发布失败时，重试不会再有新的 WAL 事件；这里负责补发最新
            // 完整状态，避免分区迁移后新节点只能看到旧压缩值。
            publishStateSnapshot(partition, current);
        }
        return null;
    }

    private void publishStateSnapshot(UserPartitionKey partition, OrderUserState state) {
        Long published = publishedSnapshotRevisions.get(partition);
        if (published != null && published >= state.revision()) {
            return;
        }
        OrderUserStateSnapshot snapshot = new OrderUserStateSnapshot(
                OrderUserStateSnapshot.CURRENT_SCHEMA_VERSION,
                partition.productLine(), partition.userId(), state.revision(), state, Instant.now());
        try {
            kafkaTemplate.send(properties.getKafka().getOrderStateEventsTopic(), snapshot.partitionKey(),
                    objectMapper.writeValueAsString(snapshot)).get(
                    properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            publishedSnapshotRevisions.put(partition, state.revision());
        } catch (Exception ex) {
            throw new KafkaException("订单完整快照发布失败 partition=" + partition.value()
                    + " revision=" + state.revision(), ex);
        }
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
        return new OrderUserState(orders, current.appliedEventIds(), current.algoOrders(), current.algoChildren(),
                current.appliedTradeIds(), current.revision());
    }

    private OrderUserState applyAccountResult(OrderUserState current, AccountCommandResultEvent result) {
        if (result == null) {
            throw new IllegalStateException("账户结果不能为空");
        }
        long orderId = parseOrderId(result.sourceReference());
        OrderRecord order = find(current, orderId);
        if (order.status() != OrderStatus.PENDING_RESERVE && order.status() != OrderStatus.CANCEL_REQUESTED) {
            return current;
        }
        boolean accepted = result.status() == AccountCommandStatus.APPLIED;
        if (order.status() == OrderStatus.CANCEL_REQUESTED) {
            if (accepted) {
                publishAccountRelease(order);
                OrderRecord canceled = withStatus(order, OrderStatus.CANCELED, "cancel requested before reservation accepted");
                publishOrderEvent(canceled, OrderEventType.CANCELED, canceled.rejectReason());
                return replace(current, canceled);
            }
            OrderRecord rejected = withStatus(order, OrderStatus.REJECTED,
                    result.errorMessage() == null ? result.errorCode() : result.errorMessage());
            publishOrderEvent(rejected, OrderEventType.REJECTED, rejected.rejectReason());
            return replace(current, rejected);
        }
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
        java.util.Set<Long> appliedTradeIds = new java.util.HashSet<>(current.appliedTradeIds());
        java.util.Set<Long> freshTradeIds = new java.util.HashSet<>();
        java.util.Map<Long, Long> makerFills = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> makerCompletions = new java.util.HashMap<>();
        for (MatchTradeEvent trade : result.trades()) {
            boolean belongsToCurrentUser = current.orders().stream()
                    .anyMatch(value -> (value.orderId() == trade.makerOrderId()
                            && value.userId() == trade.makerUserId())
                            || (value.orderId() == result.orderId() && value.userId() == result.userId()));
            if (!belongsToCurrentUser) {
                continue;
            }
            if (appliedTradeIds.contains(trade.tradeId()) || !freshTradeIds.add(trade.tradeId())) {
                continue;
            }
            makerFills.merge(trade.makerOrderId(), trade.quantitySteps(), Math::addExact);
            makerCompletions.merge(trade.makerOrderId(), trade.makerOrderCompleted(), Boolean::logicalOr);
        }
        boolean hasFreshTrade = !freshTradeIds.isEmpty();
        // 同一成交被新的撮合命令号重复投递时，不能再次推进订单成交量或修订号。
        if (!hasFreshTrade && result.filledQuantitySteps() > 0L) {
            return current;
        }
        List<OrderRecord> updatedOrders = new ArrayList<>(current.orders().size());
        boolean touched = false;
        for (OrderRecord order : current.orders()) {
            long filled = 0L;
            OrderStatus nextStatus = null;
            if (order.orderId() == result.orderId() && order.userId() == result.userId()) {
                if (hasFreshTrade) {
                    filled = result.trades().stream()
                            .filter(trade -> freshTradeIds.contains(trade.tradeId()))
                            .filter(trade -> trade.takerOrderId() == order.orderId()
                                    && trade.takerUserId() == order.userId())
                            .mapToLong(MatchTradeEvent::quantitySteps)
                            .sum();
                }
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
            OrderStatus appliedStatus = executed == order.quantitySteps() || order.status() == OrderStatus.FILLED
                    ? OrderStatus.FILLED : nextStatus;
            updatedOrders.add(new OrderRecord(order.orderId(), order.productLine(), order.userId(),
                    order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                    order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(), executed,
                    remaining, order.marginMode(), order.positionSide(), order.makerFeeRatePpm(),
                    order.takerFeeRatePpm(), order.reduceOnly(), order.postOnly(), order.reservationAccountType(),
                    order.reservationAsset(), order.reservedUnits(), appliedStatus, order.rejectReason(),
                    order.createdAt(), Instant.now(), Math.addExact(order.revision(), 1L), order.traceId()));
            touched = true;
        }
        if (!touched) {
            throw new IllegalStateException("撮合结果对应订单不存在: " + result.orderId());
        }
        appliedTradeIds.addAll(freshTradeIds);
        return new OrderUserState(updatedOrders, current.appliedEventIds(), current.algoOrders(), current.algoChildren(),
                appliedTradeIds.stream().sorted().toList(), current.revision());
    }

    private OrderUserState applyCancel(OrderUserState current, OrderUserEvent event) {
        long orderId = parseOrderId(event.eventId().substring("CANCEL:".length()));
        OrderRecord order = find(current, orderId);
        if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.FILLED
                || order.status() == OrderStatus.REJECTED) {
            return current;
        }
        if (order.status() == OrderStatus.PENDING_RESERVE) {
            // 预占结果尚未到达时不能把 CANCEL 发给撮合；否则撮合可能先看到 CANCEL，
            // 随后又收到 PLACE，账户也会留下无法释放的冻结。等预占终态到达后再补发释放命令。
            OrderRecord requested = withStatus(order, OrderStatus.CANCEL_REQUESTED, null);
            publishOrderEvent(requested, OrderEventType.CANCEL_REQUESTED, event.cancelReason());
            return replace(current, requested);
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
        return new OrderUserState(current.orders(), current.appliedEventIds(), orders, current.algoChildren(),
                current.appliedTradeIds(), current.revision());
    }

    private OrderUserState applyAlgoUpdate(OrderUserState current, AlgoOrderRecord updated) {
        if (updated == null) {
            throw new IllegalStateException("算法单更新事实缺少订单");
        }
        findAlgo(current, updated.algoOrderId());
        List<AlgoOrderRecord> orders = current.algoOrders().stream()
                .map(value -> value.algoOrderId() == updated.algoOrderId() ? updated : value)
                .toList();
        return new OrderUserState(current.orders(), current.appliedEventIds(), orders, current.algoChildren(),
                current.appliedTradeIds(), current.revision());
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
        return new OrderUserState(withOrder.orders(), withOrder.appliedEventIds(), withOrder.algoOrders(), children,
                withOrder.appliedTradeIds(), withOrder.revision());
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

    /** 预占成功后才收到撤单时，直接发出幂等释放命令；订单从未发布 PLACE，不需要通知撮合撤单。 */
    private void publishAccountRelease(OrderRecord order) {
        if (order.reservedUnits() <= 0L || order.reservationAccountType() == null
                || order.reservationAsset() == null) {
            throw new IllegalStateException("待预占订单缺少释放快照: " + order.orderId());
        }
        OrderReleaseAccountCommand release = new OrderReleaseAccountCommand(
                order.orderId(), true, order.quantitySteps(), 0L, true,
                AccountType.valueOf(order.reservationAccountType()), order.reservationAsset(),
                order.reservedUnits(), "ORDER_CANCEL_BEFORE_ACCEPT", Instant.now());
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "ORDER_RELEASE:" + order.productLine().name() + ":" + order.orderId() + ":ORDER_CANCEL_BEFORE_ACCEPT",
                order.productLine(), order.userId(), AccountUserCommandType.ORDER_RELEASE, "ORDER",
                String.valueOf(order.orderId()), null, objectMapper.writeValueAsString(release), Instant.now(), null);
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
                Instant.now(), order.traceId());
        send(properties.getKafka().getOrderCommandsTopic(), order.symbol(), objectMapper.writeValueAsString(command),
                "ORDER_COMMAND:" + commandId);
    }

    private void publishOrderEvent(OrderRecord order, OrderEventType type, String reason) {
        OrderEvent event = new OrderEvent(orderEventId(order.orderId(), type), order.orderId(),
                order.userId(), order.symbol(), type, order.status(), reason, Instant.now(), null);
        // 订单事件按交易对分区，WebSocket 和其他行情消费者据此校验并路由；用户编号只存在于载荷。
        send(properties.getKafka().getOrderEventsTopic(), order.symbol(),
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
                ? updated : value).toList(), state.appliedEventIds(), state.algoOrders(), state.algoChildren(),
                state.appliedTradeIds(), state.revision());
    }

    private OrderRecord withStatus(OrderRecord order, OrderStatus status, String reason) {
        return new OrderRecord(order.orderId(), order.productLine(), order.userId(), order.clientOrderId(),
                order.symbol(), order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                order.remainingQuantitySteps(), order.marginMode(), order.positionSide(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.reduceOnly(), order.postOnly(), order.reservationAccountType(),
                order.reservationAsset(), order.reservedUnits(), status, reason, order.createdAt(), Instant.now(),
                Math.addExact(order.revision(), 1L), order.traceId());
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
        if (orderId <= 0L || type == null) {
            throw new IllegalArgumentException("订单事件编号参数无效");
        }
        // 订单号生成器固定将低两位保留为零，因此前四种事件可以安全复用低位编码。
        // CANCELED 使用负号域，避免与四种正数编码冲突，也不再对雪花订单号做乘法溢出。
        if ((orderId & 3L) == 0L) {
            return switch (type) {
                case RESERVE_PENDING -> orderId;
                case ACCEPTED -> orderId | 1L;
                case REJECTED -> orderId | 2L;
                case CANCEL_REQUESTED -> orderId | 3L;
                case CANCELED -> -orderId;
            };
        }
        // 单元测试或外部导入的非标准订单号没有预留低位，仍使用不溢出的稳定编号。
        return switch (type) {
            case RESERVE_PENDING -> orderId;
            case ACCEPTED -> orderId == Long.MAX_VALUE ? orderId - 1L : orderId + 1L;
            case REJECTED -> orderId == Long.MAX_VALUE ? orderId - 2L : orderId + 2L;
            case CANCEL_REQUESTED -> orderId == Long.MAX_VALUE ? orderId - 3L : orderId + 3L;
            case CANCELED -> -orderId;
        };
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

    private record LocalSequence(long timestamp, int sequence) {
    }
}
