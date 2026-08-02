package com.surprising.trading.order.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
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

    static {
        RocksDB.loadLibrary();
    }

    private final int nodeId;
    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private long lastTimestamp;
    private int sequence;

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
            if (value != null) {
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
                    lastTimestamp = input.readLong();
                    sequence = input.readInt();
                }
                if (lastTimestamp < 0L || sequence < 0 || sequence > 1_023) {
                    throw new IllegalStateException("订单号序列数据损坏");
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("打开订单号本地序列失败: " + directory, ex);
        }
    }

    /** 在同步 RocksDB 写入完成后返回全局唯一、重启后仍单调的订单号。 */
    public synchronized long next() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            if (sequence >= 1_023) {
                now = Math.addExact(lastTimestamp, 1L);
                sequence = 0;
            } else {
                sequence++;
            }
        } else {
            sequence = 0;
        }
        long value = Math.addExact(Math.multiplyExact(now, 1L << 22),
                Math.addExact(((long) nodeId) << 12, ((long) sequence) << 2));
        if (value <= 0L) {
            throw new IllegalStateException("订单编号溢出");
        }
        try {
            database.put(writeOptions, KEY, encode(now, sequence));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("订单号序列落盘失败", ex);
        }
        lastTimestamp = now;
        return value;
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
