package com.surprising.eventstore;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户分区命令结果的本地幂等存储。
 *
 * <p>命令结果和账户状态不是同一个事实。结果先同步落盘，状态提交后再通过重算校验把两者
 * 连接起来，进程崩溃时不会把半完成结果当成新的资金状态。结果键严格包含用户分区和命令
 * 编号，避免不同用户使用相同命令编号时发生串读；相同分区中的相同命令只能写入相同结果，
 * 冲突结果直接失败关闭。</p>
 */
public final class UserPartitionResultStore implements AutoCloseable {

    private static final byte[] PREFIX = "result/".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_RESULT_BYTES = 16 * 1_024 * 1_024;

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB database;
    private final WriteOptions writeOptions;
    private final ReentrantLock writeLock = new ReentrantLock();

    public UserPartitionResultStore(Path directory) {
        try {
            Objects.requireNonNull(directory, "directory");
            Files.createDirectories(directory);
            options = new Options().setCreateIfMissing(true);
            database = RocksDB.open(options, directory.toString());
            writeOptions = new WriteOptions().setSync(true);
        } catch (Exception ex) {
            throw new IllegalStateException("打开用户分区命令结果库失败: " + directory, ex);
        }
    }

    /** 读取指定用户分区的命令终态；没有结果表示命令尚未完成。 */
    public Optional<byte[]> read(UserPartitionKey partition, String commandId) {
        requirePartition(partition);
        requireCommandId(commandId);
        try {
            byte[] value = database.get(key(partition, commandId));
            return value == null ? Optional.empty() : Optional.of(Arrays.copyOf(value, value.length));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("读取用户分区命令结果失败: " + partition.value() + ":" + commandId, ex);
        }
    }

    /** 同步保存指定用户分区的命令终态；相同内容重试幂等，内容冲突直接拒绝。 */
    public void put(UserPartitionKey partition, String commandId, byte[] result) {
        requirePartition(partition);
        requireCommandId(commandId);
        if (result == null || result.length == 0 || result.length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("命令结果大小无效");
        }
        writeLock.lock();
        try {
            byte[] existing = database.get(key(partition, commandId));
            if (existing != null) {
                if (!Arrays.equals(existing, result)) {
                    throw new IllegalStateException("命令结果发生幂等冲突: " + partition.value() + ":" + commandId);
                }
                return;
            }
            database.put(writeOptions, key(partition, commandId), Arrays.copyOf(result, result.length));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("写入用户分区命令结果失败: " + partition.value() + ":" + commandId, ex);
        } finally {
            writeLock.unlock();
        }
    }

    private byte[] key(UserPartitionKey partition, String commandId) {
        byte[] suffix = (partition.value() + '/' + commandId).getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[PREFIX.length + suffix.length];
        System.arraycopy(PREFIX, 0, value, 0, PREFIX.length);
        System.arraycopy(suffix, 0, value, PREFIX.length, suffix.length);
        return value;
    }

    private void requireCommandId(String commandId) {
        if (commandId == null || commandId.isBlank() || commandId.length() > 160) {
            throw new IllegalArgumentException("命令编号无效");
        }
    }

    private void requirePartition(UserPartitionKey partition) {
        Objects.requireNonNull(partition, "partition");
    }

    @Override
    public void close() {
        writeOptions.close();
        database.close();
        options.close();
    }
}
