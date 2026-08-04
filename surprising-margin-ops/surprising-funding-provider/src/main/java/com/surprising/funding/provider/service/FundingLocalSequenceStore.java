package com.surprising.funding.provider.service;

import com.surprising.eventstore.PartitionOwnerLane;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

/**
 * 资金费发布序号的本地持久状态。
 *
 * <p>序号只要求在本产品线、同一合约内单调递增，不需要占用数据库 sequence。同步写入 RocksDB
 * 后才把序号用于 Kafka 事件；进程重启不会回退，也不会因为数据库短暂不可用而阻塞行情发布。</p>
 */
public final class FundingLocalSequenceStore implements AutoCloseable {

    private static final byte[] PREFIX = "rate-sequence/".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final PartitionOwnerLane<String> owners;

    public FundingLocalSequenceStore(Path directory) {
        try {
            Objects.requireNonNull(directory, "directory");
            Files.createDirectories(directory);
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(true);
            owners = new PartitionOwnerLane<>(
                    Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8)),
                    "funding-rate-owner");
        } catch (Exception ex) {
            throw new IllegalStateException("资金费本地序号库打开失败: " + directory, ex);
        }
    }

    public long next(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("资金费序号合约不能为空");
        }
        String normalized = symbol.trim().toUpperCase();
        return owners.execute(normalized, () -> {
            try {
                byte[] key = key(normalized);
                byte[] current = database.get(key);
                long next = current == null ? 1L : Math.addExact(ByteBuffer.wrap(current).getLong(), 1L);
                database.put(writeOptions, key, ByteBuffer.allocate(Long.BYTES).putLong(next).array());
                return next;
            } catch (RocksDBException ex) {
                throw new IllegalStateException("资金费本地序号写入失败: " + normalized, ex);
            }
        });
    }

    public long current(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("资金费序号合约不能为空");
        }
        try {
            byte[] value = database.get(key(symbol.trim().toUpperCase()));
            return value == null ? 0L : ByteBuffer.wrap(value).getLong();
        } catch (RocksDBException ex) {
            throw new IllegalStateException("资金费本地序号读取失败: " + symbol, ex);
        }
    }

    @Override
    public void close() {
        owners.close();
        writeOptions.close();
        database.close();
        options.close();
    }

    private byte[] key(String symbol) {
        byte[] suffix = symbol.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[PREFIX.length + suffix.length];
        System.arraycopy(PREFIX, 0, result, 0, PREFIX.length);
        System.arraycopy(suffix, 0, result, PREFIX.length, suffix.length);
        return result;
    }
}
