package com.surprising.eventstore;

import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 RocksDB 的用户分区 WAL。
 *
 * <p>每个分区使用独立锁，事件、幂等索引和下一序列号在同一个同步写批次中提交。
 * 因此进程崩溃后不会出现事件已落盘但幂等索引或序列号缺失的半提交状态。数据库审计、
 * 订单完整快照和账本明细可以使用各自独立的连续投影水位。</p>
 */
public final class UserPartitionWal implements AutoCloseable {

    private static final byte[] NEXT_PREFIX = "next/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IDEMPOTENCY_PREFIX = "idempotency/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_PREFIX = "event/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PARTITION_PREFIX = "partition/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROJECTED_PREFIX = "projected/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEDGER_PROJECTED_PREFIX = "projected-ledger/".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1_024 * 1_024;

    static {
        RocksDB.loadLibrary();
    }

    private final Path directory;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final UserPartitionCommandLane lane;
    private final boolean ownsLane;

    public UserPartitionWal(Path directory) {
        this(directory, new UserPartitionCommandLane(), true);
    }

    public UserPartitionWal(Path directory, UserPartitionCommandLane lane) {
        this(directory, lane, false);
    }

    private UserPartitionWal(Path directory, UserPartitionCommandLane lane, boolean ownsLane) {
        try {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.lane = Objects.requireNonNull(lane, "lane");
            this.ownsLane = ownsLane;
            Files.createDirectories(directory);
            this.options = new Options().setCreateIfMissing(true);
            this.database = RocksDB.open(options, directory.toString());
            this.writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to open user partition WAL: " + directory, ex);
        }
    }

    public UserPartitionEvent append(UserPartitionKey partition,
                                     String eventId,
                                     String eventType,
                                     byte[] payload,
                                     String fingerprint,
                                     Instant occurredAt) {
        return appendBatch(partition, List.of(new AppendRequest(eventId, eventType, payload, fingerprint, occurredAt)))
                .get(0);
    }

    public List<UserPartitionEvent> appendBatch(UserPartitionKey partition, List<AppendRequest> requests) {
        Objects.requireNonNull(partition, "partition");
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        requests.forEach(request -> {
            if (request == null) {
                throw new IllegalArgumentException("WAL append request must not be null");
            }
            requireEventInput(partition, request.eventId(), request.eventType(), request.payload(),
                    request.fingerprint(), request.occurredAt());
        });
        return lane.execute(partition, () -> {
          try {
            long next = nextSequence(partition);
            List<UserPartitionEvent> result = new ArrayList<>(requests.size());
            Map<String, UserPartitionEvent> appended = new java.util.HashMap<>();
            try (WriteBatch batch = new WriteBatch()) {
                for (AppendRequest request : requests) {
                    byte[] idempotencyKey = idempotencyKey(partition, request.eventId());
                    UserPartitionEvent pending = appended.get(request.eventId());
                    if (pending != null) {
                        if (!pending.fingerprint().equals(request.fingerprint())) {
                            throw new IllegalStateException("eventId already used with different event fingerprint: "
                                    + request.eventId());
                        }
                        result.add(pending);
                        continue;
                    }
                    byte[] existing = database.get(idempotencyKey);
                    if (existing != null) {
                        ExistingEvent reference = decodeExisting(existing);
                        if (!reference.fingerprint().equals(request.fingerprint())) {
                            throw new IllegalStateException("eventId already used with different event fingerprint: "
                                    + request.eventId());
                        }
                        result.add(readEvent(partition, reference.sequence()).orElseThrow(() ->
                                new IllegalStateException("WAL idempotency index points to missing event")));
                        continue;
                    }
                    if (next <= 0L || next == Long.MAX_VALUE) {
                        throw new IllegalStateException("用户分区 WAL 序号耗尽: " + partition.value());
                    }
                    UserPartitionEvent event = new UserPartitionEvent(partition, next, request.eventId(),
                            request.eventType(), request.payload(), request.fingerprint(), request.occurredAt());
                    batch.put(eventKey(partition, next), encode(event));
                    batch.put(idempotencyKey, encodeExisting(next, request.fingerprint()));
                    result.add(event);
                    appended.put(request.eventId(), event);
                    next++;
                }
                if (next != nextSequence(partition)) {
                    batch.put(nextKey(partition), encodeLong(next));
                    batch.put(partitionKey(partition), new byte[]{1});
                    database.write(writeOptions, batch);
                }
                return List.copyOf(result);
            }
          } catch (RocksDBException ex) {
              throw new IllegalStateException("failed to append user partition WAL batch", ex);
          }
        });
    }

    public Optional<UserPartitionEvent> readEvent(UserPartitionKey partition, long sequence) {
        if (partition == null || sequence <= 0) {
            return Optional.empty();
        }
        try {
            byte[] value = database.get(eventKey(partition, sequence));
            return value == null ? Optional.empty() : Optional.of(decode(value));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read user partition WAL event", ex);
        }
    }

    /** 按事件编号读取本用户分区中的事实；用于校验依赖命令不能跨用户分区引用。 */
    public Optional<UserPartitionEvent> readEvent(UserPartitionKey partition, String eventId) {
        if (partition == null || eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] existing = database.get(idempotencyKey(partition, eventId.trim()));
            if (existing == null) {
                return Optional.empty();
            }
            ExistingEvent reference = decodeExisting(existing);
            return readEvent(partition, reference.sequence());
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read user partition WAL event by id", ex);
        }
    }

    public List<UserPartitionEvent> replay(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        List<UserPartitionEvent> events = new ArrayList<>();
        byte[] prefix = eventPrefix(partition);
        long expectedSequence = 1L;
        try (ReadOptions readOptions = new ReadOptions();
             Snapshot snapshot = database.getSnapshot()) {
            readOptions.setSnapshot(snapshot);
            try (var iterator = database.newIterator(readOptions)) {
                iterator.seek(prefix);
                while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                    UserPartitionEvent event = decode(iterator.value());
                    if (!partition.equals(event.partition()) || event.sequence() != expectedSequence) {
                        throw new IllegalStateException("用户分区 WAL 序号不连续: partition=" + partition.value()
                                + " expected=" + expectedSequence + " actual=" + event.sequence());
                    }
                    events.add(event);
                    expectedSequence = Math.addExact(expectedSequence, 1L);
                    iterator.next();
                }
            }
        }
        return List.copyOf(events);
    }

    public long lastSequence(UserPartitionKey partition) {
        long next = nextSequence(partition);
        return next == 0L ? 0L : next - 1L;
    }

    /** 返回已成功完成数据库投影的最后一条连续事件序号。 */
    public long lastProjectedSequence(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        try {
            byte[] value = database.get(projectedKey(partition));
            return value == null ? 0L : decodeLong(value);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read projected WAL sequence", ex);
        }
    }

    /** 只有数据库事务成功提交后才能推进投影水位。 */
    public void markProjected(UserPartitionKey partition, long sequence) {
        Objects.requireNonNull(partition, "partition");
        lane.execute(partition, () -> {
          try {
            if (sequence <= 0L || readEvent(partition, sequence).isEmpty()) {
                throw new IllegalArgumentException("projected WAL event must exist");
            }
            long current = lastProjectedSequence(partition);
            if (sequence != current + 1L) {
                throw new IllegalStateException("projected WAL sequence must be continuous: current=" + current
                        + " next=" + sequence);
            }
            requireProjectionRange(partition, current + 1L, sequence);
            try {
                database.put(writeOptions, projectedKey(partition), encodeLong(sequence));
            } catch (RocksDBException ex) {
                throw new IllegalStateException("failed to mark projected WAL event", ex);
            }
            return null;
          } catch (RuntimeException ex) {
              throw ex;
          }
        });
    }

    /**
     * 完整快照投影成功后一次性推进到指定序号。
     *
     * <p>完整状态已经包含中间事件的最终结果，因此允许水位跨过多个连续事件；调用方仍须
     * 保证目标序号不超过 WAL 尾部，并且只能在数据库事务成功提交后调用。</p>
     */
    public void markProjectedThrough(UserPartitionKey partition, long sequence) {
        Objects.requireNonNull(partition, "partition");
        lane.execute(partition, () -> {
          try {
            if (sequence <= 0L || readEvent(partition, sequence).isEmpty()) {
                throw new IllegalArgumentException("projected WAL event must exist");
            }
            long current = lastProjectedSequence(partition);
            if (sequence <= current) {
                return null;
            }
            if (sequence > lastSequence(partition)) {
                throw new IllegalStateException("projected WAL sequence is ahead of WAL tail: " + sequence);
            }
            requireProjectionRange(partition, current + 1L, sequence);
            try {
                database.put(writeOptions, projectedKey(partition), encodeLong(sequence));
            } catch (RocksDBException ex) {
                throw new IllegalStateException("failed to mark projected WAL sequence", ex);
            }
            return null;
          } catch (RuntimeException ex) {
              throw ex;
          }
        });
    }

    /** 返回账户账本异步投影已经完成的最后一条连续事件序号。 */
    public long lastLedgerProjectedSequence(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        try {
            byte[] value = database.get(ledgerProjectedKey(partition));
            return value == null ? 0L : decodeLong(value);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read ledger projected WAL sequence", ex);
        }
    }

    /** 账本数据库事务成功后推进独立的账本投影水位，不与命令审计水位共用。 */
    public void markLedgerProjected(UserPartitionKey partition, long sequence) {
        Objects.requireNonNull(partition, "partition");
        lane.execute(partition, () -> {
          try {
            if (sequence <= 0L || readEvent(partition, sequence).isEmpty()) {
                throw new IllegalArgumentException("ledger projected WAL event must exist");
            }
            long current = lastLedgerProjectedSequence(partition);
            if (sequence != current + 1L) {
                throw new IllegalStateException("ledger projected WAL sequence must be continuous: current="
                        + current + " next=" + sequence);
            }
            try {
                database.put(writeOptions, ledgerProjectedKey(partition), encodeLong(sequence));
            } catch (RocksDBException ex) {
                throw new IllegalStateException("failed to mark ledger projected WAL event", ex);
            }
            return null;
          } catch (RuntimeException ex) {
              throw ex;
          }
        });
    }

    /** 投影水位只能覆盖已经存在的每一条连续事件，损坏或缺口必须停住。 */
    private void requireProjectionRange(UserPartitionKey partition, long first, long last) {
        for (long sequence = first; sequence <= last; sequence++) {
            if (readEvent(partition, sequence).isEmpty()) {
                throw new IllegalStateException("projected WAL event is missing: partition=" + partition.value()
                        + " sequence=" + sequence);
            }
            if (sequence == Long.MAX_VALUE) {
                break;
            }
        }
    }

    /** 返回当前 WAL 中出现过的全部用户分区，供重启恢复扫描。 */
    public List<UserPartitionKey> partitions() {
        List<UserPartitionKey> result = new ArrayList<>();
        try (var iterator = database.newIterator()) {
            iterator.seek(PARTITION_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), PARTITION_PREFIX)) {
                String value = new String(iterator.key(), PARTITION_PREFIX.length,
                        iterator.key().length - PARTITION_PREFIX.length, StandardCharsets.UTF_8);
                result.add(UserPartitionKey.parse(value));
                iterator.next();
            }
        }
        return List.copyOf(result);
    }

    private long nextSequence(UserPartitionKey partition) {
        try {
            byte[] value = database.get(nextKey(partition));
            return value == null ? 1L : decodeLong(value);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read user partition WAL sequence", ex);
        }
    }

    private byte[] nextKey(UserPartitionKey partition) {
        return key(NEXT_PREFIX, partition.value());
    }

    private byte[] idempotencyKey(UserPartitionKey partition, String eventId) {
        return key(IDEMPOTENCY_PREFIX, partition.value() + '/' + eventId);
    }

    private byte[] eventKey(UserPartitionKey partition, long sequence) {
        return key(EVENT_PREFIX, partition.value() + '/' + String.format(java.util.Locale.ROOT, "%020d", sequence));
    }

    private byte[] eventPrefix(UserPartitionKey partition) {
        return key(EVENT_PREFIX, partition.value() + '/');
    }

    private byte[] partitionKey(UserPartitionKey partition) {
        return key(PARTITION_PREFIX, partition.value());
    }

    private byte[] projectedKey(UserPartitionKey partition) {
        return key(PROJECTED_PREFIX, partition.value());
    }

    private byte[] ledgerProjectedKey(UserPartitionKey partition) {
        return key(LEDGER_PROJECTED_PREFIX, partition.value());
    }

    private byte[] key(byte[] prefix, String value) {
        byte[] suffix = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private byte[] encode(UserPartitionEvent event) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeString(output, event.partition().value());
                output.writeLong(event.sequence());
                writeString(output, event.eventId());
                writeString(output, event.eventType());
                writeBytes(output, event.payload(), MAX_PAYLOAD_BYTES);
                writeString(output, event.fingerprint());
                output.writeLong(event.occurredAt().toEpochMilli());
            }
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encode user partition WAL event", ex);
        }
    }

    private UserPartitionEvent decode(byte[] value) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            UserPartitionKey partition = UserPartitionKey.parse(readString(input));
            long sequence = input.readLong();
            String eventId = readString(input);
            String eventType = readString(input);
            byte[] payload = readBytes(input, MAX_PAYLOAD_BYTES);
            String fingerprint = readString(input);
            Instant occurredAt = Instant.ofEpochMilli(input.readLong());
            return new UserPartitionEvent(partition, sequence, eventId, eventType, payload, fingerprint, occurredAt);
        } catch (EOFException ex) {
            throw new IllegalStateException("truncated user partition WAL event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decode user partition WAL event", ex);
        }
    }

    private byte[] encodeExisting(long sequence, String fingerprint) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(sequence);
                writeString(output, fingerprint);
            }
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encode WAL idempotency reference", ex);
        }
    }

    private ExistingEvent decodeExisting(byte[] value) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            return new ExistingEvent(input.readLong(), readString(input));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decode WAL idempotency reference", ex);
        }
    }

    private byte[] encodeLong(long value) {
        byte[] bytes = new byte[Long.BYTES];
        for (int index = Long.BYTES - 1; index >= 0; index--) {
            bytes[index] = (byte) value;
            value >>>= Byte.SIZE;
        }
        return bytes;
    }

    private long decodeLong(byte[] bytes) {
        if (bytes.length != Long.BYTES) {
            throw new IllegalStateException("invalid WAL sequence value");
        }
        long value = 0L;
        for (byte current : bytes) {
            value = (value << Byte.SIZE) | (current & 0xffL);
        }
        return value;
    }

    private void requireEventInput(UserPartitionKey partition,
                                   String eventId,
                                   String eventType,
                                   byte[] payload,
                                   String fingerprint,
                                   Instant occurredAt) {
        if (partition == null || eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()
                || payload == null || payload.length > MAX_PAYLOAD_BYTES || fingerprint == null || fingerprint.isBlank()
                || occurredAt == null) {
            throw new IllegalArgumentException("invalid user partition WAL input");
        }
    }

    private void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("WAL string is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalStateException("invalid WAL string length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated WAL string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeBytes(DataOutputStream output, byte[] value, int maxLength) throws Exception {
        if (value.length > maxLength) {
            throw new IllegalArgumentException("WAL payload is too large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private byte[] readBytes(DataInputStream input, int maxLength) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > maxLength) {
            throw new IllegalStateException("invalid WAL payload length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated WAL payload");
        }
        return bytes;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (ownsLane) {
            lane.close();
        }
        writeOptions.close();
        database.close();
        options.close();
    }

    private record ExistingEvent(long sequence, String fingerprint) {
    }

    public record AppendRequest(String eventId,
                                String eventType,
                                byte[] payload,
                                String fingerprint,
                                Instant occurredAt) {
    }
}
