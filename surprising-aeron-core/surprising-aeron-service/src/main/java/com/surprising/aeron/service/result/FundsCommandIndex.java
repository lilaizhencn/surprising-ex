package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

/**
 * 当前产品线已提交资金命令的本地磁盘身份索引，保存 UUID 到指纹的映射。激活后的 get/put 归 System Ledger 线程，构造、快照枚举和关闭沿原阶段边界执行；本地目录恢复时重建，恢复权威仍是复制日志与配对 Core 快照，不能用它跳过回放。
 */
public final class FundsCommandIndex implements AutoCloseable {
    /** 类初始化时装载 RocksDB 本地库，装载失败不能使用该资金索引。 */
    static { RocksDB.loadLibrary(); }
    private final Path rootDirectory;

    public FundsCommandIndex() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().fundsIndexDirectory());
    }

    public FundsCommandIndex(Path rootDirectory) {
        this.rootDirectory = java.util.Objects.requireNonNull(rootDirectory);
    }

    /** 本实例在配置根下创建的独立临时目录，不是可复用恢复源。 */
    private Path directory;
    /** 配置为八 MiB 的 RocksDB block cache 资源。 */
    private LRUCache cache;
    /** 数据库配置及其本地资源，限制写缓冲、文件和日志数量。 */
    private Options options;
    /** 关闭 WAL 的写配置，本地目录不另建持久化恢复协议。 */
    private WriteOptions writes;
    /** 首次新增资金命令才创建的本地 RocksDB 句柄。 */
    private RocksDB database;
    /** 成功登记的不同资金命令 UUID 数量。 */
    private long count;
    /** 已记录并对其他线程可见的不可用异常，健康检查优先抛出。 */
    private volatile RuntimeException failure;
    /** 资金索引激活后允许 get/put 的线程，未绑定时保留冷阶段访问。 */
    private volatile Thread ioOwner;
    /** 是否已开始关闭，重复 close 直接返回。 */
    private boolean closed;

    /**
     * 把当前线程登记为之后资金索引读写的所有者；相同线程可重复绑定，另一线程绑定报错。本入口要求按原生命周期串行调用，不是并发抢占锁。
     */
    public void bindIoOwner() {
        // 当前要求绑定 I/O 归属的线程
        Thread current = Thread.currentThread();
        if (ioOwner == null) ioOwner = current;
        else if (ioOwner != current) throw new IllegalStateException("funds index I/O belongs to SystemLedgerLane");
    }

    /**
     * 尚未绑定时允许原冷启动阶段访问；已绑定后只允许登记线程执行 get/put。
     */
    private void assertIoOwner() {
        // 已经登记的可空 I/O 线程
        Thread expected = ioOwner;
        if (expected != null && expected != Thread.currentThread())
            throw new IllegalStateException("funds index I/O belongs to SystemLedgerLane");
    }

    /**
     * 优先抛出已记录存储故障，否则检查是否已关闭；不在这里重新打开数据库。
     */
    public void assertHealthy() {
        if (failure != null) throw failure;
        if (closed) throw new IllegalStateException("funds command index is closed");
    }

    /**
     * 先检查健康和线程归属，尚未建库直接未命中；已建库则读取 UUID 对应指纹，数据库读取或指纹解码异常记为不可继续使用的故障。
     * @param msb 资金命令 UUID 高六十四位
     * @param lsb 资金命令 UUID 低六十四位
     */
    public CommandFingerprint get(long msb, long lsb) {
        assertHealthy();
        assertIoOwner();
        if (database == null) return null;
        try {
            // 从数据库读取的可空原指纹字节
            byte[] value = database.get(key(msb, lsb));
            return value == null ? null : CommandFingerprint.fromBytes(value);
        } catch (/* 当前读取、写入、开库、枚举或删除阶段抛出的异常，按所在边界处理 */ RocksDBException | RuntimeException exception) {
            throw fail(exception);
        }
    }

    /**
     * 空 UUID 直接返回 null，不进入健康检查；非空时转两段数值查找。
     * @param id 资金命令 UUID，按读取或写入入口分别处理空值
     */
    public CommandFingerprint get(UUID id) {
        if (id == null) return null;
        return get(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /**
     * 先查原指纹，内容相同则幂等返回，冲突直接报错且不新增；新 UUID 才懒建库并写入，成功后增加条数。数据库写入的 RocksDB 异常记录故障，不能当作历史不存在继续处理。
     * @param msb 资金命令 UUID 高六十四位
     * @param lsb 资金命令 UUID 低六十四位
     * @param fingerprint 已提交资金命令的稳定内容指纹，写入不能为空
     */
    public void put(long msb, long lsb, CommandFingerprint fingerprint) {
        assertHealthy();
        assertIoOwner();
        if (fingerprint == null) throw new IllegalArgumentException("funds identity is required");
        // 同一 UUID 已有的可空指纹，用于幂等或内容冲突检查
        CommandFingerprint previous = get(msb, lsb);
        if (previous != null) {
            if (!previous.equals(fingerprint)) throw new IllegalStateException("funds command fingerprint conflict");
            return;
        }
        open();
        try {
            database.put(writes, key(msb, lsb), fingerprint.bytes());
            count++;
        } catch (/* 当前读取、写入、开库、枚举或删除阶段抛出的异常，按所在边界处理 */ RocksDBException exception) {
            throw fail(exception);
        }
    }

    /**
     * 拒绝空 UUID，拆成两段后交给资金身份写入入口。
     * @param id 资金命令 UUID，按读取或写入入口分别处理空值
     * @param fingerprint 已提交资金命令的稳定内容指纹，写入不能为空
     */
    public void put(UUID id, CommandFingerprint fingerprint) {
        if (id == null) throw new IllegalArgumentException("funds identity is required");
        put(id.getMostSignificantBits(), id.getLeastSignificantBits(), fingerprint);
    }

    /**
     * 在既有快照阶段先写精确 int 条数，再按数据库键顺序写 UUID 和指纹；迭代完成检查状态及实际条数。输出 IOException 原样抛出，数据库异常和条数不一致记为索引故障；本方法不额外检查 I/O 线程。
     * @param output 当前快照阶段接收条数和有序身份字节的输出
     */
    public void write(DataOutput output) throws IOException {
        assertHealthy();
        output.writeInt(Math.toIntExact(count));
        if (database == null) return;
        // 本次快照已经写出的身份条数
        long written = 0;
        try (/* 本次有序枚举使用且自动关闭的 RocksDB 迭代器 */ RocksIterator iterator = database.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                output.write(iterator.key());
                output.write(iterator.value());
                written++;
            }
            iterator.status();
        } catch (/* 当前读取、写入、开库、枚举或删除阶段抛出的异常，按所在边界处理 */ RocksDBException exception) {
            throw fail(exception);
        }
        if (written != count) throw fail(new IllegalStateException("funds index snapshot count mismatch"));
    }

    /**
     * 首次新增资金身份时，在配置根目录下创建本实例独立临时目录，设置固定缓存、写缓冲和日志预算后开库；关闭本地 WAL，恢复只依赖 Cluster 日志与 Core 快照。创建或开库异常记录故障。
     */
    private void open() {
        if (database != null) return;
        try {
            Files.createDirectories(rootDirectory);
            directory = Files.createTempDirectory(rootDirectory, "surprising-funds-index-");
            cache = new LRUCache(8L * 1024 * 1024);
            options = new Options().setCreateIfMissing(true)
                    .setWriteBufferSize(4L * 1024 * 1024).setMaxWriteBufferNumber(2)
                    .setMaxOpenFiles(64).setMaxLogFileSize(1024 * 1024).setKeepLogFileNum(2)
                    .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache)
                            .setCacheIndexAndFilterBlocks(true));
            // 不建立另一套持久化恢复协议；只用已提交 Cluster 日志和 Core 快照重建此索引。
            writes = new WriteOptions().setDisableWAL(true);
            database = RocksDB.open(options, directory.toString());
        } catch (/* 当前读取、写入、开库、枚举或删除阶段抛出的异常，按所在边界处理 */ IOException | RocksDBException | RuntimeException exception) {
            throw fail(exception);
        }
    }

    /**
     * 记录带原原因的不可用异常并返回它，之后健康检查继续抛出该故障；当前实现每次调用可覆盖先前故障引用。
     * @param exception 造成索引不可继续使用的原始异常
     */
    private RuntimeException fail(Exception exception) {
        failure = new IllegalStateException("funds command index unavailable; stop processing and recover from Cluster Log/snapshot", exception);
        return failure;
    }

    /**
     * 把 UUID 两段数值按高字节在前编码为固定十六字节键，供数据库确定的键顺序使用。
     * @param msb 资金命令 UUID 高六十四位
     * @param lsb 资金命令 UUID 低六十四位
     */
    private static byte[] key(long msb, long lsb) {
        // 用于编码 UUID 的固定十六字节键数组
        byte[] bytes = new byte[16];
        // 逐字节右移编码的 UUID 高位工作值
        long m = msb;
        // 逐字节右移编码的 UUID 低位工作值
        long l = lsb;
        for (/* 当前从后向前写入高字节序键的数组下标 */ int i = 7; i >= 0; i--) {
            bytes[i] = (byte) m;
            m >>>= 8;
        }
        for (/* 当前从后向前写入高字节序键的数组下标 */ int i = 15; i >= 8; i--) {
            bytes[i] = (byte) l;
            l >>>= 8;
        }
        return bytes;
    }

    /**
     * 首次关闭先置关闭标记，按顺序释放数据库及配置资源，再逆序删除仅本实例创建的目录。删除 IOException 包装成 UncheckedIOException；较早资源关闭抛错时不保证后续步骤已执行。
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (database != null) database.close();
        if (writes != null) writes.close();
        if (options != null) options.close();
        if (cache != null) cache.close();
        if (directory != null) {
            try (/* 只遍历本实例目录的可关闭路径流 */ var paths = Files.walk(directory)) {
                for (Path /* 逆序遍历到的待删除本实例文件或目录 */ path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (/* 当前读取、写入、开库、枚举或删除阶段抛出的异常，按所在边界处理 */ IOException exception) {
                throw new UncheckedIOException("failed to remove this runtime's funds index", exception);
            }
        }
    }
}
