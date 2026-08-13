package com.surprising.trading.matching.store;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.eventstore.PartitionOwnerLane;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import tools.jackson.databind.ObjectMapper;

/**
 * 撮合单写者的本地事实状态。
 *
 * <p>订单命令按交易对进入 Kafka 分区，撮合状态在本地 RocksDB 同步提交。数据库订单、成交和
 * 结果表只能由后续投影器重建，不能参与命令幂等、做市方快照或订单簿恢复。</p>
 */
public final class MatchingLocalStateStore implements AutoCloseable {

    private static final byte[] RESULT_PREFIX = "result/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORDER_PREFIX = "order/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRADE_PREFIX = "trade/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTBOX_PREFIX = "local-outbox/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTBOX_IDEMPOTENCY_PREFIX = "local-outbox-id/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTBOX_SEQUENCE_PREFIX = "local-outbox-sequence/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ASSIGNMENT_EPOCH_KEY = "assignment-epoch".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYMBOL_SHARD_PREFIX = "symbol-shard/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHARD_SEQUENCE_PREFIX = "shard-sequence/".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final ObjectMapper objectMapper;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final PartitionOwnerLane<String> symbolOwners;
    private final boolean ownsSymbolOwners;
    private final int bookShardCount;
    private final Map<String, Object> outboxStreamLocks = new ConcurrentHashMap<>();
    private final Map<Integer, Object> shardSequenceLocks = new ConcurrentHashMap<>();

    public MatchingLocalStateStore(Path directory, ObjectMapper objectMapper) {
        this(directory, objectMapper, new PartitionOwnerLane<>(
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 32)),
                "matching-symbol-owner"), true);
    }

    public MatchingLocalStateStore(Path directory, ObjectMapper objectMapper, int bookShardCount) {
        this(directory, objectMapper, new PartitionOwnerLane<>(
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 32)),
                "matching-symbol-owner"), true, bookShardCount);
    }

    public MatchingLocalStateStore(Path directory,
                                   ObjectMapper objectMapper,
                                   PartitionOwnerLane<String> symbolOwners) {
        this(directory, objectMapper, symbolOwners, false, 1);
    }

    public MatchingLocalStateStore(Path directory,
                                   ObjectMapper objectMapper,
                                   PartitionOwnerLane<String> symbolOwners,
                                   int bookShardCount) {
        this(directory, objectMapper, symbolOwners, false, bookShardCount);
    }

    public synchronized long assignmentEpoch() {
        try {
            return readLong(database.get(ASSIGNMENT_EPOCH_KEY), 0L);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取撮合 assignment epoch 失败", ex);
        }
    }

    public synchronized long advanceAssignmentEpoch() {
        long next = Math.addExact(assignmentEpoch(), 1L);
        try {
            database.put(writeOptions, ASSIGNMENT_EPOCH_KEY, encodeLong(next));
            return next;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("持久化撮合 assignment epoch 失败", ex);
        }
    }

    private MatchingLocalStateStore(Path directory,
                                    ObjectMapper objectMapper,
                                    PartitionOwnerLane<String> symbolOwners,
                                    boolean ownsSymbolOwners) {
        this(directory, objectMapper, symbolOwners, ownsSymbolOwners, 1);
    }

    private MatchingLocalStateStore(Path directory,
                                    ObjectMapper objectMapper,
                                    PartitionOwnerLane<String> symbolOwners,
                                    boolean ownsSymbolOwners,
                                    int bookShardCount) {
        try {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            this.symbolOwners = Objects.requireNonNull(symbolOwners, "symbolOwners");
            this.ownsSymbolOwners = ownsSymbolOwners;
            if (bookShardCount <= 0) {
                throw new IllegalArgumentException("bookShardCount must be positive");
            }
            this.bookShardCount = bookShardCount;
            Files.createDirectories(Objects.requireNonNull(directory, "directory"));
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("打开撮合本地状态库失败: " + directory, ex);
        }
    }

    public void assertSymbolShard(String symbol, int shardId) {
        String requiredSymbol = Objects.requireNonNull(symbol, "symbol");
        if (shardId < 0 || shardId >= bookShardCount) {
            throw new IllegalArgumentException("matching book shard id out of range: " + shardId);
        }
        withSymbolOwner(requiredSymbol, () -> {
            byte[] key = key(SYMBOL_SHARD_PREFIX, requiredSymbol);
            try {
                byte[] existing = database.get(key);
                if (existing != null && readLong(existing, -1L) != shardId) {
                    throw new IllegalStateException("symbol 已绑定到其他撮合 shard symbol=" + requiredSymbol
                            + " expected=" + readLong(existing, -1L) + " actual=" + shardId);
                }
                if (existing == null) {
                    database.put(writeOptions, key, encodeLong(shardId));
                }
                return null;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("读取撮合 symbol shard 归属失败 symbol=" + requiredSymbol, ex);
            }
        });
    }

    /** PLACE 命令在进入 exchange-core 前先登记到本地状态，避免等待订单表投影。 */
    public void prepare(OrderCommandEvent command) {
        Objects.requireNonNull(command, "command");
        if (command.commandType() != com.surprising.trading.api.model.OrderCommandType.PLACE) {
            return;
        }
        withSymbolOwner(command.symbol(), () -> {
            try {
                byte[] existing = database.get(orderKey(command.orderId()));
                if (existing != null) {
                    StoredOrder previous = decode(existing, StoredOrder.class);
                    if (!previous.command().equals(command)) {
                        throw new IllegalStateException("撮合订单编号对应不同命令 orderId=" + command.orderId());
                    }
                    return null;
                }
                StoredOrder created = new StoredOrder(command, 0L, command.quantitySteps(),
                        OrderStatus.ACCEPTED, command.commandTime());
                database.put(writeOptions, orderKey(command.orderId()), encode(created));
                return null;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("登记撮合本地订单失败 orderId=" + command.orderId(), ex);
            }
        });
    }

    public CommandState commandState(long commandId, long orderId) {
        return new CommandState(result(commandId).isPresent(), order(orderId).isPresent());
    }

    public Map<Long, CommandState> commandStates(Map<Long, Long> commands) {
        if (commands == null || commands.isEmpty()) {
            return Map.of();
        }
        Map<Long, CommandState> result = new LinkedHashMap<>(commands.size());
        commands.forEach((commandId, orderId) -> result.put(commandId, commandState(commandId, orderId)));
        return Map.copyOf(result);
    }

    public Optional<MatchResultEvent> result(long commandId) {
        try {
            byte[] value = database.get(resultKey(commandId));
            return value == null ? Optional.empty() : Optional.of(decode(value, MatchResultEvent.class));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取撮合结果失败 commandId=" + commandId, ex);
        }
    }

    public Optional<StoredOrder> order(long orderId) {
        try {
            byte[] value = database.get(orderKey(orderId));
            return value == null ? Optional.empty() : Optional.of(decode(value, StoredOrder.class));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取撮合订单失败 orderId=" + orderId, ex);
        }
    }

    public ShardCheckpoint shardCheckpoint(int shardId) {
        requireShard(shardId);
        try {
            byte[] value = database.get(key(SHARD_SEQUENCE_PREFIX, Integer.toString(shardId)));
            return new ShardCheckpoint(shardId, value == null ? 0L : readLong(value, 0L));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取撮合 shard checkpoint 失败 shardId=" + shardId, ex);
        }
    }

    public MatchedOrderSnapshot snapshot(long orderId) {
        StoredOrder order = order(orderId)
                .orElseThrow(() -> new IllegalStateException("撮合订单快照不存在 orderId=" + orderId));
        return order.snapshot();
    }

    public Map<Long, MatchedOrderSnapshot> snapshots(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MatchedOrderSnapshot> result = new LinkedHashMap<>();
        for (Long orderId : orderIds.stream().distinct().toList()) {
            result.put(orderId, snapshot(orderId));
        }
        return Map.copyOf(result);
    }

    /**
     * 将结果、成交和两侧订单状态放进同一个同步 WriteBatch，保证结果已落盘时订单数量也已落盘。
     */
    public boolean commit(MatchResultEvent result, List<MatchTradeEvent> persistedTrades) {
        return commit(result, persistedTrades, List.of());
    }

    public boolean commit(MatchResultEvent result,
                          List<MatchTradeEvent> persistedTrades,
                          List<MatchingOutboxWrite> outboxWrites) {
        Objects.requireNonNull(result, "result");
        List<MatchTradeEvent> trades = persistedTrades == null ? List.of() : List.copyOf(persistedTrades);
        List<MatchingOutboxWrite> writes = outboxWrites == null ? List.of() : List.copyOf(outboxWrites);
        return withSymbolOwner(result.symbol(), () -> commitLocked(result, trades, writes));
    }

    public boolean saveResult(MatchResultEvent result) {
        Objects.requireNonNull(result, "result");
        return withSymbolOwner(result.symbol(), () -> {
            try {
                byte[] key = resultKey(result.commandId());
                byte[] encoded = encode(result);
                byte[] existing = database.get(key);
                if (existing != null) {
                    if (!Arrays.equals(existing, encoded)) {
                        throw new IllegalStateException("撮合命令结果幂等冲突 commandId=" + result.commandId());
                    }
                    return false;
                }
                database.put(writeOptions, key, encoded);
                return true;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("写入撮合结果失败 commandId=" + result.commandId(), ex);
            }
        });
    }

    public void applyActiveOrderStatus(MatchResultEvent result) {
        withSymbolOwner(result.symbol(), () -> {
            StoredOrder current = requireOrder(result.orderId());
            StoredOrder updated = activeOrderUpdate(current, result);
            putOrder(updated);
            return null;
        });
    }

    public void applyMakerFills(List<MatchTradeEvent> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        Map<String, List<MatchTradeEvent>> bySymbol = new LinkedHashMap<>();
        for (MatchTradeEvent trade : trades) {
            bySymbol.computeIfAbsent(trade.symbol(), ignored -> new ArrayList<>()).add(trade);
        }
        for (Map.Entry<String, List<MatchTradeEvent>> entry : bySymbol.entrySet()) {
            withSymbolOwner(entry.getKey(), () -> {
                applyMakerFillsLocked(entry.getValue());
                return null;
            });
        }
    }

    public void saveTrades(List<MatchTradeEvent> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        Map<String, List<MatchTradeEvent>> bySymbol = new LinkedHashMap<>();
        for (MatchTradeEvent trade : trades) {
            bySymbol.computeIfAbsent(trade.symbol(), ignored -> new ArrayList<>()).add(trade);
        }
        for (Map.Entry<String, List<MatchTradeEvent>> entry : bySymbol.entrySet()) {
            withSymbolOwner(entry.getKey(), () -> {
                saveTradesLocked(entry.getValue());
                return null;
            });
        }
    }

    private void saveTradesLocked(List<MatchTradeEvent> trades) {
        try (WriteBatch batch = new WriteBatch()) {
            for (MatchTradeEvent trade : trades) {
                byte[] key = tradeKey(trade.tradeId());
                byte[] encoded = encode(trade);
                byte[] existing = database.get(key);
                if (existing != null && !Arrays.equals(existing, encoded)) {
                    throw new IllegalStateException("成交编号幂等冲突 tradeId=" + trade.tradeId());
                }
                if (existing == null) {
                    batch.put(key, encoded);
                }
            }
            database.write(writeOptions, batch);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("写入撮合成交失败", ex);
        }
    }

    /** 将撮合结果通知和账户命令写入本地可靠队列；Kafka 发送失败时由下一轮继续重试。 */
    public void enqueueOutbox(List<MatchingOutboxWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        List<MatchingOutboxWrite> copy = List.copyOf(writes);
        Map<String, List<MatchingOutboxWrite>> byStream = new LinkedHashMap<>();
        for (MatchingOutboxWrite write : copy) {
            if (write == null || write.eventKey() == null || write.eventKey().isBlank()) {
                throw new IllegalArgumentException("撮合本地通知 eventKey 不能为空");
            }
            byStream.computeIfAbsent(write.eventKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (Map.Entry<String, List<MatchingOutboxWrite>> entry : byStream.entrySet()) {
            withSymbolOwner(entry.getKey(), () -> {
                enqueueOutboxLocked(entry.getValue());
                return null;
            });
        }
    }

    private void enqueueOutboxLocked(List<MatchingOutboxWrite> writes) {
        withOutboxStreamLocks(writes, () -> {
            try (WriteBatch batch = new WriteBatch()) {
                appendOutbox(batch, writes);
                database.write(writeOptions, batch);
            } catch (RocksDBException ex) {
                throw new IllegalStateException("写入撮合本地通知队列失败", ex);
            }
            return null;
        });
    }

    public List<LocalOutboxRecord> pendingOutbox(int limit) {
        return pendingOutboxInternal(null, limit);
    }

    public List<LocalOutboxRecord> pendingOutbox(int shardId, int limit) {
        requireShard(shardId);
        return pendingOutboxInternal(shardId, limit);
    }

    private List<LocalOutboxRecord> pendingOutboxInternal(Integer shardId, int limit) {
        List<LocalOutboxRecord> result = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            iterator.seek(OUTBOX_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), OUTBOX_PREFIX)) {
                LocalOutboxRecord record = decode(iterator.value(), LocalOutboxRecord.class);
                if (!record.published() && (shardId == null || record.shardId() == shardId)) {
                    result.add(record);
                }
                iterator.next();
            }
        }
        result.sort(Comparator.comparingLong(LocalOutboxRecord::sequence));
        if (result.size() > Math.max(1, limit)) {
            result = new ArrayList<>(result.subList(0, Math.max(1, limit)));
        }
        return List.copyOf(result);
    }

    public void markOutboxPublished(long sequence) {
        LocalOutboxRecord record = findOutbox(sequence)
                .orElseThrow(() -> new IllegalStateException("本地通知不存在 sequence=" + sequence));
        markOutboxPublished(record);
    }

    public void markOutboxPublished(LocalOutboxRecord record) {
        Objects.requireNonNull(record, "record");
        withSymbolOwner(record.eventKey(), () -> {
            markOutboxPublishedLocked(record);
            return null;
        });
    }

    public void markOutboxPublished(List<LocalOutboxRecord> records) {
        markOutboxPublished(records, assignmentEpoch());
    }

    public void markOutboxPublished(List<LocalOutboxRecord> records, long expectedEpoch) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (assignmentEpoch() != expectedEpoch) {
            throw new IllegalStateException("撮合 outbox assignment epoch 已变化: expected=" + expectedEpoch
                    + " actual=" + assignmentEpoch());
        }
        Map<String, List<LocalOutboxRecord>> byStream = new LinkedHashMap<>();
        for (LocalOutboxRecord record : records) {
            if (record != null && !record.published()) {
                if (record.assignmentEpoch() != expectedEpoch) {
                    throw new IllegalStateException("撮合 outbox 记录属于旧 assignment epoch sequence="
                            + record.sequence());
                }
                byStream.computeIfAbsent(record.eventKey(), ignored -> new ArrayList<>()).add(record);
            }
        }
        for (Map.Entry<String, List<LocalOutboxRecord>> entry : byStream.entrySet()) {
            withSymbolOwner(entry.getKey(), () -> {
                try (WriteBatch batch = new WriteBatch()) {
                    for (LocalOutboxRecord record : entry.getValue()) {
                        byte[] key = findOutboxKey(record.eventKey(), record.sequence());
                        if (key == null) {
                            throw new IllegalStateException("本地通知不存在 sequence=" + record.sequence());
                        }
                        byte[] value = database.get(key);
                        if (value == null) {
                            throw new IllegalStateException("本地通知不存在 sequence=" + record.sequence());
                        }
                        LocalOutboxRecord current = decode(value, LocalOutboxRecord.class);
                        if (!current.published()) {
                            batch.put(key, encode(new LocalOutboxRecord(current.sequence(), current.aggregateType(),
                                    current.aggregateId(), current.topic(), current.eventKey(), current.eventType(),
                    current.payload(), current.createdAt(), true, current.shardId(), current.assignmentEpoch())));
                        }
                    }
                    if (batch.count() > 0) {
                        database.write(writeOptions, batch);
                    }
                    return null;
                } catch (RocksDBException ex) {
                    throw new IllegalStateException("批量标记撮合本地通知失败", ex);
                }
            });
        }
    }

    public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant createdAt,
                                                                     long lastOrderId,
                                                                     int limit) {
        return recoverableOpenOrdersAfter(null, createdAt, lastOrderId, limit);
    }

    public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(int shardId,
                                                                     Instant createdAt,
                                                                     long lastOrderId,
                                                                     int limit) {
        requireShard(shardId);
        return recoverableOpenOrdersAfter(Integer.valueOf(shardId), createdAt, lastOrderId, limit);
    }

    private List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Integer shardId,
                                                                       Instant createdAt,
                                                                       long lastOrderId,
                                                                       int limit) {
        List<StoredOrder> open = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            iterator.seek(ORDER_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), ORDER_PREFIX)) {
                StoredOrder order = decode(iterator.value(), StoredOrder.class);
                if (order.remainingQuantitySteps() > 0L && isOpen(order.status())
                        && (shardId == null || shardForSymbol(order.command().symbol()) == shardId)
                        && (order.command().commandTime().isAfter(createdAt)
                        || (order.command().commandTime().equals(createdAt)
                        && order.command().orderId() > lastOrderId))) {
                    open.add(order);
                }
                iterator.next();
            }
        }
        return open.stream()
                .sorted(Comparator.comparing((StoredOrder value) -> value.command().commandTime())
                        .thenComparingLong(value -> value.command().orderId()))
                .limit(Math.max(1, limit))
                .map(value -> new RecoveredOrderBookOrder(value.command().orderId(), value.command().userId(),
                        value.command().symbol(), value.command().instrumentVersion(), value.command().side(),
                        value.command().timeInForce(), value.command().priceTicks(),
                        value.remainingQuantitySteps(), value.command().commandTime()))
                .toList();
    }

    private boolean commitLocked(MatchResultEvent result,
                                 List<MatchTradeEvent> persistedTrades,
                                 List<MatchingOutboxWrite> outboxWrites) {
        try {
            byte[] resultKey = resultKey(result.commandId());
            byte[] resultBytes = encode(result);
            byte[] existing = database.get(resultKey);
            if (existing != null) {
                if (!Arrays.equals(existing, resultBytes)) {
                    throw new IllegalStateException("撮合命令结果幂等冲突 commandId=" + result.commandId());
                }
                return false;
            }
            StoredOrder taker = requireOrder(result.orderId());
            Map<Long, StoredOrder> updates = new LinkedHashMap<>();
            updates.put(taker.command().orderId(), activeOrderUpdate(taker, result));
            Map<Long, MakerFill> makerFills = new LinkedHashMap<>();
            for (MatchTradeEvent trade : result.trades()) {
                makerFills.merge(trade.makerOrderId(), new MakerFill(trade.quantitySteps(),
                        trade.makerOrderCompleted(), trade.eventTime()), (left, right) -> new MakerFill(
                        Math.addExact(left.quantitySteps(), right.quantitySteps()),
                        left.completed() || right.completed(),
                        left.eventTime().isAfter(right.eventTime()) ? left.eventTime() : right.eventTime()));
            }
            for (Map.Entry<Long, MakerFill> entry : makerFills.entrySet()) {
                StoredOrder maker = requireOrder(entry.getKey());
                MakerFill fill = entry.getValue();
                long executed = Math.addExact(maker.executedQuantitySteps(), fill.quantitySteps());
                if (executed > maker.command().quantitySteps()) {
                    throw new IllegalStateException("撮合做市方成交超过订单数量 orderId=" + entry.getKey());
                }
                updates.put(entry.getKey(), new StoredOrder(maker.command(), executed,
                        Math.subtractExact(maker.command().quantitySteps(), executed),
                        fill.completed() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                        fill.eventTime()));
            }
            int shardId = shardForSymbol(result.symbol());
            synchronized (shardSequenceLocks.computeIfAbsent(shardId, ignored -> new Object())) {
                withOutboxStreamLocks(outboxWrites, () -> {
                try {
                    try (WriteBatch batch = new WriteBatch()) {
                        batch.put(resultKey, resultBytes);
                        for (StoredOrder update : updates.values()) {
                            batch.put(orderKey(update.command().orderId()), encode(update));
                        }
                        for (MatchTradeEvent trade : persistedTrades) {
                            byte[] key = tradeKey(trade.tradeId());
                            byte[] value = encode(trade);
                            byte[] prior = database.get(key);
                            if (prior != null && !Arrays.equals(prior, value)) {
                                throw new IllegalStateException("成交编号幂等冲突 tradeId=" + trade.tradeId());
                            }
                            if (prior == null) {
                                batch.put(key, value);
                            }
                        }
                        appendOutbox(batch, outboxWrites, shardId);
                        batch.put(key(SHARD_SEQUENCE_PREFIX, Integer.toString(shardId)),
                                encodeLong(shardCheckpoint(shardId).lastSequence() + 1L));
                        database.write(writeOptions, batch);
                    }
                    return null;
                } catch (RocksDBException ex) {
                    throw new IllegalStateException("提交撮合本地事实失败 commandId=" + result.commandId(), ex);
                }
                });
            }
            return true;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("提交撮合本地事实失败 commandId=" + result.commandId(), ex);
        }
    }

    private void appendOutbox(WriteBatch batch, List<MatchingOutboxWrite> writes) throws RocksDBException {
        appendOutbox(batch, writes, null);
    }

    private void appendOutbox(WriteBatch batch,
                              List<MatchingOutboxWrite> writes,
                              Integer originShardId) throws RocksDBException {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        Map<String, List<MatchingOutboxWrite>> byStream = new LinkedHashMap<>();
        for (MatchingOutboxWrite write : writes) {
            if (write == null || write.eventKey() == null || write.eventKey().isBlank()
                    || write.now() == null || write.payload() == null) {
                throw new IllegalArgumentException("撮合本地通知不能为空");
            }
            byStream.computeIfAbsent(write.eventKey(), ignored -> new ArrayList<>()).add(write);
        }
        for (Map.Entry<String, List<MatchingOutboxWrite>> entry : byStream.entrySet()) {
            appendOutboxStream(batch, entry.getKey(), entry.getValue(), originShardId);
        }
    }

    private void appendOutboxStream(WriteBatch batch,
                                    String eventKey,
                                    List<MatchingOutboxWrite> writes,
                                    Integer originShardId) throws RocksDBException {
        byte[] sequenceKey = key(OUTBOX_SEQUENCE_PREFIX, streamKey(eventKey));
        byte[] stored = database.get(sequenceKey);
        long next = stored == null ? findNextStreamSequence(eventKey) : readLong(stored, 1L);
        boolean advanced = false;
        String stream = streamKey(eventKey);
        for (MatchingOutboxWrite write : writes) {
            String identity = outboxIdentity(write);
            byte[] identityKey = key(OUTBOX_IDEMPOTENCY_PREFIX, identity);
            if (database.get(identityKey) != null) {
                continue;
            }
            if (next == Long.MAX_VALUE) {
                throw new IllegalStateException("撮合本地通知序列耗尽");
            }
            LocalOutboxRecord record = new LocalOutboxRecord(next++,
                    write.aggregateType(), write.aggregateId(),
                    write.topic(), write.eventKey(), write.eventType(), write.payload(), write.now(), false,
                    originShardId == null ? shardForEventKey(write.eventKey()) : originShardId, assignmentEpoch());
            batch.put(outboxKey(stream, identity), encode(record));
            batch.put(identityKey, encodeLong(record.sequence()));
            advanced = true;
        }
        if (advanced) {
            batch.put(sequenceKey, encodeLong(next));
        }
    }

    private long findNextStreamSequence(String eventKey) {
        long maximum = 0L;
        byte[] prefix = key(OUTBOX_PREFIX, streamKey(eventKey) + "/");
        try (var iterator = database.newIterator()) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                LocalOutboxRecord record = decode(iterator.value(), LocalOutboxRecord.class);
                if (eventKey.equals(record.eventKey())) {
                    maximum = Math.max(maximum, record.sequence());
                }
                iterator.next();
            }
        }
        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException("撮合本地通知序列耗尽");
        }
        return maximum + 1L;
    }

    private int shardForSymbol(String symbol) {
        return Math.floorMod(Objects.requireNonNull(symbol, "symbol").hashCode(), bookShardCount);
    }

    private int shardForEventKey(String eventKey) {
        return Math.floorMod(Objects.requireNonNull(eventKey, "eventKey").hashCode(), bookShardCount);
    }

    private void requireShard(int shardId) {
        if (shardId < 0 || shardId >= bookShardCount) {
            throw new IllegalArgumentException("matching book shard id out of range: " + shardId);
        }
    }

    private <T> T withOutboxStreamLocks(List<MatchingOutboxWrite> writes,
                                         java.util.function.Supplier<T> action) {
        List<String> streams = writes.stream()
                .filter(Objects::nonNull)
                .map(MatchingOutboxWrite::eventKey)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        return withOutboxStreamLocks(streams, 0, action);
    }

    private <T> T withOutboxStreamLocks(List<String> streams,
                                        int index,
                                        java.util.function.Supplier<T> action) {
        if (index >= streams.size()) {
            return action.get();
        }
        Object lock = outboxStreamLocks.computeIfAbsent(streams.get(index), ignored -> new Object());
        synchronized (lock) {
            return withOutboxStreamLocks(streams, index + 1, action);
        }
    }

    private void applyMakerFillsLocked(List<MatchTradeEvent> trades) {
        Map<Long, MakerFill> fills = new LinkedHashMap<>();
        for (MatchTradeEvent trade : trades) {
            fills.merge(trade.makerOrderId(), new MakerFill(trade.quantitySteps(),
                    trade.makerOrderCompleted(), trade.eventTime()), (left, right) -> new MakerFill(
                    Math.addExact(left.quantitySteps(), right.quantitySteps()),
                    left.completed() || right.completed(),
                    left.eventTime().isAfter(right.eventTime()) ? left.eventTime() : right.eventTime()));
        }
        for (Map.Entry<Long, MakerFill> entry : fills.entrySet()) {
            StoredOrder current = requireOrder(entry.getKey());
            MakerFill fill = entry.getValue();
            long executed = Math.addExact(current.executedQuantitySteps(), fill.quantitySteps());
            if (executed > current.command().quantitySteps()) {
                throw new IllegalStateException("撮合做市方成交超过订单数量 orderId=" + entry.getKey());
            }
            putOrder(new StoredOrder(current.command(), executed,
                    Math.subtractExact(current.command().quantitySteps(), executed),
                    fill.completed() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    fill.eventTime()));
        }
    }

    private StoredOrder activeOrderUpdate(StoredOrder current, MatchResultEvent result) {
        if (result.commandType() == com.surprising.trading.api.model.OrderCommandType.CANCEL) {
            return "SUCCESS".equals(result.resultCode())
                    ? new StoredOrder(current.command(), current.executedQuantitySteps(), 0L,
                    OrderStatus.CANCELED, result.eventTime()) : current;
        }
        long executed = Math.addExact(current.executedQuantitySteps(), result.filledQuantitySteps());
        if (executed > current.command().quantitySteps()) {
            throw new IllegalStateException("撮合成交超过订单数量 orderId=" + current.command().orderId());
        }
        long remaining = result.orderStatus() == OrderStatus.REJECTED
                || result.orderStatus() == OrderStatus.CANCELED
                ? 0L : Math.subtractExact(current.command().quantitySteps(), executed);
        return new StoredOrder(current.command(), executed, remaining,
                result.orderStatus(), result.eventTime());
    }

    private StoredOrder requireOrder(long orderId) {
        return order(orderId).orElseThrow(() -> new IllegalStateException("撮合订单不存在 orderId=" + orderId));
    }

    private void putOrder(StoredOrder order) {
        try {
            database.put(writeOptions, orderKey(order.command().orderId()), encode(order));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("更新撮合订单失败 orderId=" + order.command().orderId(), ex);
        }
    }

    private boolean isOpen(OrderStatus status) {
        return status == OrderStatus.ACCEPTED || status == OrderStatus.PARTIALLY_FILLED
                || status == OrderStatus.CANCEL_REQUESTED;
    }

    private <T> T withSymbolOwner(String symbol, java.util.function.Supplier<T> action) {
        String requiredSymbol = Objects.requireNonNull(symbol, "symbol");
        if (symbolOwners.isOwnerThread(requiredSymbol)) {
            return action.get();
        }
        return symbolOwners.execute(requiredSymbol, action);
    }

    public <T> T runAsOwner(String symbol, java.util.function.Supplier<T> action) {
        String requiredSymbol = Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        if (symbolOwners.isOwnerThread(requiredSymbol)) {
            return action.get();
        }
        return symbolOwners.execute(requiredSymbol, action);
    }

    private byte[] resultKey(long commandId) {
        return key(RESULT_PREFIX, Long.toString(commandId));
    }

    private byte[] orderKey(long orderId) {
        return key(ORDER_PREFIX, Long.toString(orderId));
    }

    private byte[] tradeKey(long tradeId) {
        return key(TRADE_PREFIX, Long.toString(tradeId));
    }

    private byte[] outboxKey(String stream, String identity) {
        return key(OUTBOX_PREFIX, stream + "/" + identity);
    }

    private String streamKey(String eventKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(eventKey.getBytes(StandardCharsets.UTF_8));
    }

    private long publicSequence(String identity) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << Byte.SIZE) | (digest[index] & 0xffL);
        }
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private Optional<LocalOutboxRecord> findOutbox(long sequence) {
        try (var iterator = database.newIterator()) {
            iterator.seek(OUTBOX_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), OUTBOX_PREFIX)) {
                LocalOutboxRecord record = decode(iterator.value(), LocalOutboxRecord.class);
                if (record.sequence() == sequence) {
                    return Optional.of(record);
                }
                iterator.next();
            }
            return Optional.empty();
        }
    }

    private byte[] findOutboxKey(String eventKey, long sequence) {
        try (var iterator = database.newIterator()) {
            iterator.seek(OUTBOX_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), OUTBOX_PREFIX)) {
                LocalOutboxRecord record = decode(iterator.value(), LocalOutboxRecord.class);
                if (record.sequence() == sequence && eventKey.equals(record.eventKey())) {
                    return Arrays.copyOf(iterator.key(), iterator.key().length);
                }
                iterator.next();
            }
            return null;
        }
    }

    private void markOutboxPublishedLocked(LocalOutboxRecord record) {
        try {
            byte[] key = findOutboxKey(record.eventKey(), record.sequence());
            if (key == null) {
                throw new IllegalStateException("本地通知不存在 sequence=" + record.sequence());
            }
            byte[] value = database.get(key);
            if (value == null) {
                throw new IllegalStateException("本地通知不存在 sequence=" + record.sequence());
            }
            LocalOutboxRecord current = decode(value, LocalOutboxRecord.class);
            if (!current.published()) {
                database.put(writeOptions, key, encode(new LocalOutboxRecord(current.sequence(), current.aggregateType(),
                        current.aggregateId(), current.topic(), current.eventKey(), current.eventType(),
                        current.payload(), current.createdAt(), true, current.shardId(), current.assignmentEpoch())));
            }
        } catch (RocksDBException ex) {
            throw new IllegalStateException("标记撮合本地通知失败 sequence=" + record.sequence(), ex);
        }
    }

    private String outboxIdentity(MatchingOutboxWrite write) {
        String value = write.aggregateType() + "|" + write.aggregateId() + "|" + write.topic() + "|"
                + write.eventKey() + "|" + write.eventType() + "|" + write.payload();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String outboxIdentity(LocalOutboxRecord record) {
        return outboxIdentity(new MatchingOutboxWrite(record.aggregateType(), record.aggregateId(), record.topic(),
                record.eventKey(), record.eventType(), record.payload(), record.createdAt()));
    }

    private byte[] encodeLong(long value) {
        byte[] bytes = new byte[Long.BYTES];
        for (int index = Long.BYTES - 1; index >= 0; index--) {
            bytes[index] = (byte) value;
            value >>>= Byte.SIZE;
        }
        return bytes;
    }

    private long readLong(byte[] value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value.length != Long.BYTES) {
            throw new IllegalStateException("撮合本地序列损坏");
        }
        long result = 0L;
        for (byte current : value) {
            result = (result << Byte.SIZE) | (current & 0xffL);
        }
        return result;
    }

    private byte[] key(byte[] prefix, String value) {
        byte[] suffix = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private byte[] encode(Object value) {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }

    private <T> T decode(byte[] value, Class<T> type) {
        try {
            return objectMapper.readValue(new String(value, StandardCharsets.UTF_8), type);
        } catch (Exception ex) {
            throw new IllegalStateException("撮合本地状态损坏", ex);
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (ownsSymbolOwners) {
            symbolOwners.close();
        }
        writeOptions.close();
        database.close();
        options.close();
    }

    public record CommandState(boolean resultExists, boolean orderExists) {
    }

    public record StoredOrder(OrderCommandEvent command,
                              long executedQuantitySteps,
                              long remainingQuantitySteps,
                              OrderStatus status,
                              Instant updatedAt) {
        public StoredOrder {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(updatedAt, "updatedAt");
            long total = Math.addExact(executedQuantitySteps, remainingQuantitySteps);
            boolean terminalWithoutFill = status == OrderStatus.CANCELED || status == OrderStatus.REJECTED;
            // 活跃/完全成交订单必须保持数量守恒；撤单或拒单会丢弃尚未成交数量，
            // 因此只要求已成交量不超过原始数量且剩余量为零。
            if (executedQuantitySteps < 0L || remainingQuantitySteps < 0L
                    || executedQuantitySteps > command.quantitySteps()
                    || (terminalWithoutFill ? remainingQuantitySteps != 0L
                    : total != command.quantitySteps())) {
                throw new IllegalArgumentException("撮合本地订单数量不一致");
            }
        }

        public MatchedOrderSnapshot snapshot() {
            if (remainingQuantitySteps <= 0L) {
                throw new IllegalStateException("已完成订单不能作为做市方快照 orderId=" + command.orderId());
            }
            return new MatchedOrderSnapshot(command.instrumentVersion(), command.marginMode(), command.positionSide(),
                    command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.quantitySteps(),
                    remainingQuantitySteps, command.reduceOnly(), command.reservationAccountType(),
                    command.reservationAsset(), command.reservedUnits());
        }
    }

    public record LocalOutboxRecord(long sequence,
                                    String aggregateType,
                                    long aggregateId,
                                    String topic,
                                    String eventKey,
                                    String eventType,
                                    String payload,
                                    Instant createdAt,
                                    boolean published,
                                    int shardId,
                                    long assignmentEpoch) {
        public LocalOutboxRecord(long sequence,
                                 String aggregateType,
                                 long aggregateId,
                                 String topic,
                                 String eventKey,
                                 String eventType,
                                 String payload,
                                 Instant createdAt,
                                 boolean published) {
            this(sequence, aggregateType, aggregateId, topic, eventKey, eventType, payload, createdAt, published, 0, 0L);
        }

        public LocalOutboxRecord(long sequence,
                                 String aggregateType,
                                 long aggregateId,
                                 String topic,
                                 String eventKey,
                                 String eventType,
                                 String payload,
                                 Instant createdAt,
                                 boolean published,
                                 int shardId) {
            this(sequence, aggregateType, aggregateId, topic, eventKey, eventType, payload, createdAt, published,
                    shardId, 0L);
        }

        public LocalOutboxRecord {
            if (sequence <= 0L || aggregateType == null || topic == null || eventKey == null
                    || eventType == null || payload == null || createdAt == null) {
                throw new IllegalArgumentException("撮合本地通知字段无效");
            }
        }
    }

    public record ShardCheckpoint(int shardId, long lastSequence) {
        public ShardCheckpoint {
            if (shardId < 0 || lastSequence < 0L) {
                throw new IllegalArgumentException("撮合 shard checkpoint 字段无效");
            }
        }
    }

    private record MakerFill(long quantitySteps, boolean completed, Instant eventTime) {
    }
}
