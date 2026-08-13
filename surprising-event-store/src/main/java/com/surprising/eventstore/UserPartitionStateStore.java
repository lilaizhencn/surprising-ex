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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 用户分区状态快照存储。
 *
 * <p>状态快照和已应用序列号在同一个 RocksDB 批次中同步提交。状态更新只能按连续序号推进，
 * 因此进程在事件处理任意阶段崩溃后，恢复逻辑可以从最后一个完整状态继续重放，而不会重复
 * 应用资金事件。</p>
 */
public final class UserPartitionStateStore implements AutoCloseable {

    private static final byte[] STATE_PREFIX = "state/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEQUENCE_PREFIX = "state-sequence/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PARTITION_PREFIX = "state-partition/".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_STATE_BYTES = 64 * 1_024 * 1_024;

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final UserPartitionCommandLane lane;
    private final boolean ownsLane;

    public UserPartitionStateStore(Path directory) {
        this(directory, new UserPartitionCommandLane(), true);
    }

    public UserPartitionStateStore(Path directory, UserPartitionCommandLane lane) {
        this(directory, lane, false);
    }

    private UserPartitionStateStore(Path directory, UserPartitionCommandLane lane, boolean ownsLane) {
        try {
            Objects.requireNonNull(directory, "directory");
            this.lane = Objects.requireNonNull(lane, "lane");
            this.ownsLane = ownsLane;
            Files.createDirectories(directory);
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(false);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to open user partition state store: " + directory, ex);
        }
    }

    /** 返回状态快照；没有显式初始化的用户不能被当成零余额用户。 */
    public Optional<StateSnapshot> read(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        try (ReadOptions readOptions = new ReadOptions();
             Snapshot snapshot = database.getSnapshot()) {
            readOptions.setSnapshot(snapshot);
            byte[] state = database.get(readOptions, stateKey(partition));
            if (state == null) {
                return Optional.empty();
            }
            byte[] sequence = database.get(readOptions, sequenceKey(partition));
            if (sequence == null) {
                throw new IllegalStateException("state snapshot sequence is missing: " + partition.value());
            }
            return Optional.of(new StateSnapshot(partition, decodeLong(sequence), state));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read user partition state: " + partition.value(), ex);
        }
    }

    /** 启动恢复或内部 RPC 初始化使用；已存在状态不能被覆盖。 */
    public void initialize(UserPartitionKey partition, byte[] state) {
        requireState(partition, state);
        execute(partition, () -> {
            try {
                byte[] existing = database.get(stateKey(partition));
                if (existing != null) {
                    if (!Arrays.equals(existing, state)) {
                        throw new IllegalStateException("用户分区初始化快照冲突: " + partition.value());
                    }
                    return null;
                }
                try (WriteBatch batch = new WriteBatch()) {
                    batch.put(stateKey(partition), Arrays.copyOf(state, state.length));
                    batch.put(sequenceKey(partition), encodeLong(0L));
                    batch.put(partitionKey(partition), new byte[]{1});
                    database.write(writeOptions, batch);
                }
                return null;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("failed to initialize user partition state: " + partition.value(), ex);
            }
        });
    }

    /**
     * 仅在该用户分区还没有应用任何事实时替换启动快照。
     *
     * <p>账户状态 Topic 是按用户键压缩的广播快照。新 JVM 可能先读到旧快照再读到较新的
     * 快照；序号仍为零时允许单调替换，已经应用过 WAL 事实后则必须拒绝，避免用公共快照
     * 覆盖本地单写者的预占、成交和幂等索引。</p>
     */
    public void replaceIfUnapplied(UserPartitionKey partition, byte[] state) {
        requireState(partition, state);
        execute(partition, () -> {
            try {
                if (currentSequence(partition) != 0L) {
                    throw new IllegalStateException("用户分区已经应用事实，不能替换启动快照: " + partition.value());
                }
                try (WriteBatch batch = new WriteBatch()) {
                    batch.put(stateKey(partition), Arrays.copyOf(state, state.length));
                    batch.put(sequenceKey(partition), encodeLong(0L));
                    batch.put(partitionKey(partition), new byte[]{1});
                    database.write(writeOptions, batch);
                }
                return null;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("替换用户分区启动快照失败: " + partition.value(), ex);
            }
        });
    }

    /**
     * 按连续事件序号提交状态。相同序号和相同内容的重试视为幂等；相同序号但内容不同直接拒绝。
     */
    public void apply(UserPartitionKey partition, long sequence, byte[] state) {
        requireState(partition, state);
        if (sequence <= 0L) {
            throw new IllegalArgumentException("state sequence must be positive");
        }
        execute(partition, () -> {
            try {
                long current = currentSequence(partition);
                byte[] existing = database.get(stateKey(partition));
                if (sequence <= current) {
                    if (sequence == current && Arrays.equals(existing, state)) {
                        return null;
                    }
                    throw new IllegalStateException("conflicting user partition state sequence: partition="
                            + partition.value() + " current=" + current + " requested=" + sequence);
                }
                if (sequence != current + 1L) {
                    throw new IllegalStateException("user partition state sequence must be continuous: partition="
                            + partition.value() + " current=" + current + " requested=" + sequence);
                }
                try (WriteBatch batch = new WriteBatch()) {
                    batch.put(stateKey(partition), Arrays.copyOf(state, state.length));
                    batch.put(sequenceKey(partition), encodeLong(sequence));
                    batch.put(partitionKey(partition), new byte[]{1});
                    database.write(writeOptions, batch);
                }
                return null;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("failed to apply user partition state: " + partition.value(), ex);
            }
        });
    }

    public long lastAppliedSequence(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
        return currentSequence(partition);
    }

    public StateSnapshot checkpoint(UserPartitionKey partition) {
        return read(partition).orElseThrow(() ->
                new IllegalStateException("user partition checkpoint is missing: " + partition.value()));
    }

    public boolean isCheckpointAt(UserPartitionKey partition, long sequence) {
        if (sequence < 0L) {
            throw new IllegalArgumentException("checkpoint sequence must not be negative");
        }
        return lastAppliedSequence(partition) == sequence;
    }

    /** 返回状态库中已初始化的用户分区，供重启恢复扫描。 */
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

    private long currentSequence(UserPartitionKey partition) {
        try {
            byte[] value = database.get(sequenceKey(partition));
            return value == null ? 0L : decodeLong(value);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("failed to read user partition state sequence: " + partition.value(), ex);
        }
    }

    private byte[] stateKey(UserPartitionKey partition) {
        return key(STATE_PREFIX, partition.value());
    }

    private byte[] sequenceKey(UserPartitionKey partition) {
        return key(SEQUENCE_PREFIX, partition.value());
    }

    private byte[] partitionKey(UserPartitionKey partition) {
        return key(PARTITION_PREFIX, partition.value());
    }

    private byte[] key(byte[] prefix, String value) {
        byte[] suffix = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private void requireState(UserPartitionKey partition, byte[] state) {
        Objects.requireNonNull(partition, "partition");
        if (state == null || state.length == 0 || state.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("user partition state size is invalid");
        }
    }

    private byte[] encodeLong(long value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Long.BYTES);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(value);
            }
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encode state sequence", ex);
        }
    }

    private long decodeLong(byte[] value) {
        if (value == null || value.length != Long.BYTES) {
            throw new IllegalStateException("invalid state sequence");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            return input.readLong();
        } catch (Exception ex) {
            throw new IllegalStateException("invalid state sequence", ex);
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

    private <T> T execute(UserPartitionKey partition, Supplier<T> action) {
        return lane.execute(partition, action);
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

    public record StateSnapshot(UserPartitionKey partition, long sequence, byte[] state) {
        public StateSnapshot {
            Objects.requireNonNull(partition, "partition");
            if (sequence < 0L || state == null || state.length == 0) {
                throw new IllegalArgumentException("invalid user partition state snapshot");
            }
            state = Arrays.copyOf(state, state.length);
        }

        @Override
        public byte[] state() {
            return Arrays.copyOf(state, state.length);
        }
    }
}
