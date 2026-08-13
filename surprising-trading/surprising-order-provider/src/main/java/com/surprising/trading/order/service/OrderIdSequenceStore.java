package com.surprising.trading.order.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

/**
 * 订单号生成器的持久化序列。
 *
 * <p>订单号不能只依赖 JVM 内存时钟；进程重启或系统时钟回拨时必须继续使用单调序列，
 * 否则会破坏订单幂等和撮合结果关联。</p>
 */
public final class OrderIdSequenceStore implements AutoCloseable {

    private static final byte[] KEY = "order-id-sequence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int SEQUENCE_MAX = 1_023;
    private static final int SEQUENCE_BITS = 10;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1L;
    private static final long RESERVATION_WINDOW_MILLIS = 1L << 20;

    static {
        RocksDB.loadLibrary();
    }

    private final int nodeId;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final AtomicLong logicalState;
    private final Object reservationLock = new Object();
    private volatile long reservedThrough;

    public OrderIdSequenceStore(Path directory, int nodeId) {
        if (nodeId < 0 || nodeId > 1_023) {
            throw new IllegalArgumentException("订单号节点编号必须在 0..1023");
        }
        try {
            Files.createDirectories(Objects.requireNonNull(directory, "directory"));
            this.nodeId = nodeId;
            this.options = new Options().setCreateIfMissing(true);
            this.database = RocksDB.open(options, directory.toString());
            this.writeOptions = new WriteOptions().setSync(true);
            byte[] value = database.get(KEY);
            long persistedTimestamp = -1L;
            if (value != null) {
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
                    persistedTimestamp = input.readLong();
                    int persistedSequence = input.readInt();
                    if (persistedTimestamp < 0L || persistedSequence < 0 || persistedSequence > SEQUENCE_MAX) {
                        throw new IllegalStateException("订单号序列数据损坏");
                    }
                }
            }
            long startTimestamp = Math.max(System.currentTimeMillis(), Math.addExact(persistedTimestamp, 1L));
            reservedThrough = Math.addExact(startTimestamp, RESERVATION_WINDOW_MILLIS);
            persistReservation(reservedThrough);
            logicalState = new AtomicLong(pack(startTimestamp, SEQUENCE_MAX));
        } catch (Exception ex) {
            throw new IllegalStateException("打开订单号本地序列失败: " + directory, ex);
        }
    }

    public long next() {
        while (true) {
            long previous = logicalState.get();
            long previousTimestamp = previous >>> SEQUENCE_BITS;
            int previousSequence = (int) (previous & SEQUENCE_MASK);
            long timestamp = Math.max(System.currentTimeMillis(), previousTimestamp);
            int sequence;
            if (timestamp == previousTimestamp && previousSequence < SEQUENCE_MAX) {
                sequence = previousSequence + 1;
            } else {
                timestamp = Math.max(timestamp, Math.addExact(previousTimestamp, 1L));
                sequence = 0;
            }
            ensureReserved(timestamp);
            long next = pack(timestamp, sequence);
            if (logicalState.compareAndSet(previous, next)) {
                long value = Math.addExact(Math.multiplyExact(timestamp, 1L << 22),
                        Math.addExact(((long) nodeId) << 12, ((long) sequence) << 2));
                if (value <= 0L) {
                    throw new IllegalStateException("订单编号溢出");
                }
                return value;
            }
        }
    }

    private long pack(long timestamp, int sequence) {
        return Math.addExact(Math.multiplyExact(timestamp, 1L << SEQUENCE_BITS), sequence);
    }

    private void ensureReserved(long timestamp) {
        if (timestamp <= reservedThrough) {
            return;
        }
        synchronized (reservationLock) {
            if (timestamp <= reservedThrough) {
                return;
            }
            reservedThrough = Math.addExact(timestamp, RESERVATION_WINDOW_MILLIS);
            persistReservation(reservedThrough);
        }
    }

    private void persistReservation(long timestamp) {
        try {
            database.put(writeOptions, KEY, encode(timestamp, SEQUENCE_MAX));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("订单号预留窗口落盘失败", ex);
        }
    }

    private byte[] encode(long timestamp, int currentSequence) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Long.BYTES + Integer.BYTES);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(timestamp);
                output.writeInt(currentSequence);
            }
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("订单号序列编码失败", ex);
        }
    }

    @Override
    public void close() {
        writeOptions.close();
        database.close();
        options.close();
    }
}
