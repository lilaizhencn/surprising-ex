package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
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
 * Product-owner confined disk index of committed funds command identities.
 * Cluster Log and paired Core snapshots remain the recovery authority; this local directory is
 * rebuilt on recovery and is never opened as a substitute for replay. Cache and memtables are
 * bounded independently of the cumulative number of commands. Snapshot enumeration is key ordered.
 */
final class FundsCommandIndex implements AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private Path directory;
    private LRUCache cache;
    private Options options;
    private WriteOptions writes;
    private RocksDB database;
    private long count;
    private RuntimeException failure;
    private boolean closed;

    void assertHealthy() {
        if (failure != null) throw failure;
        if (closed) throw new IllegalStateException("funds command index is closed");
    }

    CommandFingerprint get(UUID id) {
        assertHealthy();
        if (database == null) return null;
        try {
            byte[] value = database.get(key(id));
            return value == null ? null : CommandFingerprint.fromBytes(value);
        } catch (RocksDBException | RuntimeException exception) {
            throw fail(exception);
        }
    }

    void put(UUID id, CommandFingerprint fingerprint) {
        assertHealthy();
        if (id == null || fingerprint == null) throw new IllegalArgumentException("funds identity is required");
        CommandFingerprint previous = get(id);
        if (previous != null) {
            if (!previous.equals(fingerprint)) throw new IllegalStateException("funds command fingerprint conflict");
            return;
        }
        open();
        try {
            database.put(writes, key(id), fingerprint.bytes());
            count++;
        } catch (RocksDBException exception) {
            throw fail(exception);
        }
    }

    void write(DataOutput output) throws IOException {
        assertHealthy();
        output.writeInt(Math.toIntExact(count));
        if (database == null) return;
        long written = 0;
        try (RocksIterator iterator = database.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                output.write(iterator.key());
                output.write(iterator.value());
                written++;
            }
            iterator.status();
        } catch (RocksDBException exception) {
            throw fail(exception);
        }
        if (written != count) throw fail(new IllegalStateException("funds index snapshot count mismatch"));
    }

    private void open() {
        if (database != null) return;
        try {
            String configured = System.getProperty("surprising.aeron.funds-index-directory", System.getProperty("java.io.tmpdir"));
            Path root = Path.of(configured);
            Files.createDirectories(root);
            directory = Files.createTempDirectory(root, "surprising-funds-index-");
            cache = new LRUCache(8L * 1024 * 1024);
            options = new Options().setCreateIfMissing(true)
                    .setWriteBufferSize(4L * 1024 * 1024).setMaxWriteBufferNumber(2)
                    .setMaxOpenFiles(64).setMaxLogFileSize(1024 * 1024).setKeepLogFileNum(2)
                    .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache)
                            .setCacheIndexAndFilterBlocks(true));
            // No separate durability protocol: only committed Cluster Log + Core snapshot recover this index.
            writes = new WriteOptions().setDisableWAL(true);
            database = RocksDB.open(options, directory.toString());
        } catch (IOException | RocksDBException | RuntimeException exception) {
            throw fail(exception);
        }
    }

    private RuntimeException fail(Exception exception) {
        failure = new IllegalStateException("funds command index unavailable; stop processing and recover from Cluster Log/snapshot", exception);
        return failure;
    }

    private static byte[] key(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (database != null) database.close();
        if (writes != null) writes.close();
        if (options != null) options.close();
        if (cache != null) cache.close();
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException exception) {
                throw new UncheckedIOException("failed to remove this runtime's funds index", exception);
            }
        }
    }
}
