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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 RocksDB 的用户分区 WAL。
 *
 * <p>每个分区使用独立锁，事件、幂等索引和下一序列号在同一个同步写批次中提交。
 * 因此进程崩溃后不会出现事件已落盘但幂等索引或序列号缺失的半提交状态。</p>
 */
public final class UserPartitionWal implements AutoCloseable {

    private static final byte[] NEXT_PREFIX = "next/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IDEMPOTENCY_PREFIX = "idempotency/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_PREFIX = "event/".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1_024 * 1_024;

    static {
        RocksDB.loadLibrary();
    }

    private final Path directory;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final ConcurrentHashMap<UserPartitionKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public UserPartitionWal(Path directory) {
        try {
            this.directory = Objects.requireNonNull(directory, "directory");
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
        requireEventInput(partition, eventId, eventType, payload, fingerprint, occurredAt);
        ReentrantLock lock = locks.computeIfAbsent(partition, ignored -> new ReentrantLock());
        lock.lock();
        try {
            byte[] idempotencyKey = idempotencyKey(partition, eventId);
            byte[] existing = database.get(idempotencyKey);
            if (existing != null) {
                ExistingEvent reference = decodeExisting(existing);
                if (!reference.fingerprint().equals(fingerprint)) {
                    throw new IllegalStateException("eventId already used with different event fingerprint: " + eventId);
                }
                return readEvent(partition, reference.sequence())
                        .orElseThrow(() -> new IllegalStateException("WAL idempotency index points to missing event"));
            }

            long sequence = nextSequence(partition);
            UserPartitionEvent event = new UserPartitionEvent(partition, sequence, eventId, eventType, payload,
                    fingerprint, occurredAt);
            try (WriteBatch batch = new WriteBatch()) {
                batch.put(eventKey(partition, sequence), encode(event));
                batch.put(idempotencyKey, encodeExisting(sequence, fingerprint));
                batch.put(nextKey(partition), encodeLong(sequence + 1L));
                database.write(writeOptions, batch);
            }
            return event;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to append user partition WAL event", ex);
        } finally {
            lock.unlock();
        }
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

    public List<UserPartitionEvent> replay(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        List<UserPartitionEvent> events = new ArrayList<>();
        byte[] prefix = eventPrefix(partition);
        try (ReadOptions readOptions = new ReadOptions();
             Snapshot snapshot = database.getSnapshot()) {
            readOptions.setSnapshot(snapshot);
            try (var iterator = database.newIterator(readOptions)) {
                iterator.seek(prefix);
                while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                    events.add(decode(iterator.value()));
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
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
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
        return input.readNBytes(length);
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
        writeOptions.close();
        database.close();
        options.close();
    }

    private record ExistingEvent(long sequence, String fingerprint) {
    }
}
