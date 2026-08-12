package com.surprising.risk.provider.service;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 风险计算结果的本地持久化队列。
 *
 * <p>计算线程只把已经完成的风险评估追加到这里，数据库序列、快照和强平候选由独立投影器
 * 异步分配和写入。投影水位也在同一个 RocksDB 中保存，数据库事务提交后才推进，因此进程
 * 在提交前后崩溃都可以重放而不会跳过风险事实。</p>
 */
public final class RiskLocalProjectionStore implements AutoCloseable {

    private static final byte[] NEXT_KEY = "meta/next".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROJECTED_KEY = "meta/projected".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BATCH_PREFIX = "batch/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINGERPRINT_PREFIX = "fingerprint/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ASSIGNMENT_PREFIX = "assignment/".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final ObjectMapper objectMapper;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final ReentrantLock lock = new ReentrantLock();

    public RiskLocalProjectionStore(Path directory, ObjectMapper objectMapper) {
        try {
            Files.createDirectories(Objects.requireNonNull(directory, "directory"));
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            this.options = new Options().setCreateIfMissing(true);
            this.database = RocksDB.open(options, directory.toString());
            this.writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("无法打开风险本地投影队列: " + directory, ex);
        }
    }

    /** 以内容指纹幂等追加一次评估批次。 */
    public long append(RiskProjectionBatch batch) {
        Objects.requireNonNull(batch, "batch");
        byte[] payload = encode(batch);
        String fingerprint = fingerprint(payload);
        lock.lock();
        try {
            byte[] existing = database.get(fingerprintKey(fingerprint));
            if (existing != null) {
                return decodeLong(existing);
            }
            long sequence = readLong(NEXT_KEY) + 1L;
            try (WriteBatch writeBatch = new WriteBatch()) {
                writeBatch.put(batchKey(sequence), payload);
                writeBatch.put(fingerprintKey(fingerprint), encodeLong(sequence));
                writeBatch.put(NEXT_KEY, encodeLong(sequence));
                database.write(writeOptions, writeBatch);
            }
            return sequence;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("追加风险本地投影事实失败", ex);
        } finally {
            lock.unlock();
        }
    }

    public List<PendingBatch> pending(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long projected = projectedSequence();
        List<PendingBatch> result = new ArrayList<>();
        try (ReadOptions readOptions = new ReadOptions(); Snapshot snapshot = database.getSnapshot()) {
            readOptions.setSnapshot(snapshot);
            try (var iterator = database.newIterator(readOptions)) {
                iterator.seek(batchKey(projected + 1L));
                while (iterator.isValid() && startsWith(iterator.key(), BATCH_PREFIX)
                        && result.size() < limit) {
                    long sequence = decodeBatchSequence(iterator.key());
                    result.add(new PendingBatch(sequence, decode(iterator.value()), assignment(sequence)));
                    iterator.next();
                }
            }
        }
        return List.copyOf(result);
    }

    /** 在数据库投影事务之前保存已经分配的数据库 ID，保证提交后崩溃重试使用同一组 ID。 */
    public void assign(long sequence, ProjectionIds ids) {
        if (sequence <= 0L || ids == null) {
            throw new IllegalArgumentException("风险投影 ID 分配参数无效");
        }
        lock.lock();
        try {
            if (assignment(sequence).isPresent() && !assignment(sequence).orElseThrow().equals(ids)) {
                throw new IllegalStateException("风险投影批次已存在不同数据库 ID: " + sequence);
            }
            database.put(writeOptions, assignmentKey(sequence), encode(ids));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("保存风险投影数据库 ID 失败", ex);
        } finally {
            lock.unlock();
        }
    }

    public Optional<ProjectionIds> assignment(long sequence) {
        try {
            byte[] value = database.get(assignmentKey(sequence));
            return value == null ? Optional.empty() : Optional.of(decodeIds(value));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取风险投影数据库 ID 失败", ex);
        }
    }

    /** 只有对应数据库事务成功提交后，才能推进连续投影水位。 */
    public void markProjected(long sequence) {
        lock.lock();
        try {
            long current = projectedSequence();
            if (sequence != current + 1L) {
                throw new IllegalStateException("风险投影水位不连续 current=" + current + " next=" + sequence);
            }
            database.put(writeOptions, PROJECTED_KEY, encodeLong(sequence));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("提交风险投影水位失败", ex);
        } finally {
            lock.unlock();
        }
    }

    public long projectedSequence() {
        try {
            return readLong(PROJECTED_KEY);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取风险投影水位失败", ex);
        }
    }

    private byte[] encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException ex) {
            throw new IllegalStateException("序列化风险本地投影事实失败", ex);
        }
    }

    private RiskProjectionBatch decode(byte[] value) {
        try {
            return objectMapper.readValue(new String(value, StandardCharsets.UTF_8), RiskProjectionBatch.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("解析风险本地投影事实失败", ex);
        }
    }

    private ProjectionIds decodeIds(byte[] value) {
        try {
            return objectMapper.readValue(new String(value, StandardCharsets.UTF_8), ProjectionIds.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("解析风险投影数据库 ID 失败", ex);
        }
    }

    private long readLong(byte[] key) throws RocksDBException {
        byte[] value = database.get(key);
        return value == null ? 0L : decodeLong(value);
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
            throw new IllegalStateException("风险本地序列值长度错误");
        }
        long value = 0L;
        for (byte current : bytes) {
            value = (value << Byte.SIZE) | (current & 0xffL);
        }
        return value;
    }

    private String fingerprint(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private byte[] batchKey(long sequence) {
        return key(BATCH_PREFIX, String.format(java.util.Locale.ROOT, "%020d", sequence));
    }

    private long decodeBatchSequence(byte[] key) {
        return Long.parseLong(new String(key, BATCH_PREFIX.length,
                key.length - BATCH_PREFIX.length, StandardCharsets.UTF_8));
    }

    private byte[] fingerprintKey(String fingerprint) {
        return key(FINGERPRINT_PREFIX, fingerprint);
    }

    private byte[] assignmentKey(long sequence) {
        return key(ASSIGNMENT_PREFIX, String.format(java.util.Locale.ROOT, "%020d", sequence));
    }

    private byte[] key(byte[] prefix, String suffix) {
        byte[] value = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(value, 0, result, prefix.length, value.length);
        return result;
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

    public record RiskProjectionBatch(ProductLine productLine, List<RiskProjectionGroup> groups) {
        public RiskProjectionBatch {
            productLine = Objects.requireNonNull(productLine, "productLine");
            groups = List.copyOf(groups == null ? List.of() : groups);
        }
    }

    public record RiskProjectionGroup(long userId,
                                      String accountType,
                                      String settleAsset,
                                      long walletBalanceUnits,
                                      long unrealizedPnlUnits,
                                      long equityUnits,
                                      long maintenanceMarginUnits,
                                      long marginRatioPpm,
                                      RiskStatus status,
                                      List<RiskProjectionPosition> positions,
                                      List<CalculatedPositionRisk> flatPositions,
                                      Instant eventTime,
                                      String traceId) {
        public RiskProjectionGroup {
            positions = List.copyOf(positions == null ? List.of() : positions);
            flatPositions = List.copyOf(flatPositions == null ? List.of() : flatPositions);
        }
    }

    public record RiskProjectionPosition(CalculatedPositionRisk position,
                                         long marginRatioPpm,
                                         RiskStatus status,
                                         long equityUnits,
                                         boolean liquidation) {
    }

    public record ProjectionIds(long snapshotStart, long eventStart, long candidateStart) {
    }

    public record PendingBatch(long sequence, RiskProjectionBatch batch, Optional<ProjectionIds> ids) {
    }
}
