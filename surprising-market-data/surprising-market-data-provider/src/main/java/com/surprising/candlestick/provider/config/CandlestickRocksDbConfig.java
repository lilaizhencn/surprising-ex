package com.surprising.candlestick.provider.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.CompressionType;
import org.rocksdb.Filter;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksObject;
import org.rocksdb.TableFormatConfig;
import org.rocksdb.WriteBufferManager;

/**
 * RocksDB tuning hook used by Kafka Streams persistent state stores.
 *
 * <p>Kafka Streams creates one RocksDB instance per state store/task. This setter applies the
 * same bounded-memory defaults to all candlestick stores so hot-state reads stay fast while write
 * buffers and compaction remain predictable under multi-node deployments.</p>
 */
public class CandlestickRocksDbConfig implements RocksDBConfigSetter {

    public static final String BLOCK_CACHE_SIZE_BYTES_CONFIG = "surprising.candlestick.rocksdb.block.cache.size.bytes";
    public static final String WRITE_BUFFER_SIZE_BYTES_CONFIG = "surprising.candlestick.rocksdb.write.buffer.size.bytes";
    public static final String WRITE_BUFFER_MANAGER_SIZE_BYTES_CONFIG = "surprising.candlestick.rocksdb.write.buffer.manager.size.bytes";
    public static final String MAX_WRITE_BUFFER_NUMBER_CONFIG = "surprising.candlestick.rocksdb.max.write.buffer.number";
    public static final String BLOCK_SIZE_BYTES_CONFIG = "surprising.candlestick.rocksdb.block.size.bytes";
    public static final String MAX_BACKGROUND_JOBS_CONFIG = "surprising.candlestick.rocksdb.max.background.jobs";
    public static final String TARGET_FILE_SIZE_BASE_BYTES_CONFIG = "surprising.candlestick.rocksdb.target.file.size.base.bytes";
    public static final String BYTES_PER_SYNC_CONFIG = "surprising.candlestick.rocksdb.bytes.per.sync";
    public static final String COMPRESSION_TYPE_CONFIG = "surprising.candlestick.rocksdb.compression.type";
    public static final String BLOOM_FILTER_ENABLED_CONFIG = "surprising.candlestick.rocksdb.bloom.filter.enabled";
    public static final String BLOOM_FILTER_BITS_PER_KEY_CONFIG = "surprising.candlestick.rocksdb.bloom.filter.bits.per.key";

    private static final Object SHARED_RESOURCE_LOCK = new Object();
    private static Cache sharedBlockCache;
    private static WriteBufferManager sharedWriteBufferManager;
    private static int openStores;

    private final Map<String, List<RocksObject>> storeResources = new ConcurrentHashMap<>();

    @Override
    public void setConfig(String storeName, Options options, Map<String, Object> configs) {
        List<RocksObject> resources = new ArrayList<>();
        Cache cache = sharedBlockCache(configs);

        BlockBasedTableConfig tableConfig = blockBasedTableConfig(options);
        tableConfig.setBlockCache(cache)
                .setBlockSize(getLong(configs, BLOCK_SIZE_BYTES_CONFIG, 16 * 1024L))
                .setCacheIndexAndFilterBlocks(true)
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setPinL0FilterAndIndexBlocksInCache(true)
                .setOptimizeFiltersForMemory(true);

        if (getBoolean(configs, BLOOM_FILTER_ENABLED_CONFIG, true)) {
            Filter bloomFilter = new BloomFilter(getDouble(configs, BLOOM_FILTER_BITS_PER_KEY_CONFIG, 10.0d), false);
            tableConfig.setFilterPolicy(bloomFilter);
            resources.add(bloomFilter);
        }

        options.setTableFormatConfig(tableConfig)
                .setWriteBufferSize(getLong(configs, WRITE_BUFFER_SIZE_BYTES_CONFIG, 64L * 1024L * 1024L))
                .setWriteBufferManager(sharedWriteBufferManager(configs, cache))
                .setMaxWriteBufferNumber(getInt(configs, MAX_WRITE_BUFFER_NUMBER_CONFIG, 3))
                .setMaxBackgroundJobs(getInt(configs, MAX_BACKGROUND_JOBS_CONFIG, 2))
                .setTargetFileSizeBase(getLong(configs, TARGET_FILE_SIZE_BASE_BYTES_CONFIG, 64L * 1024L * 1024L))
                .setBytesPerSync(getLong(configs, BYTES_PER_SYNC_CONFIG, 1024L * 1024L))
                .setCompressionType(compressionType(configs))
                .setLevelCompactionDynamicLevelBytes(true);

        storeResources.put(storeName, resources);
    }

    private BlockBasedTableConfig blockBasedTableConfig(Options options) {
        TableFormatConfig existing = options.tableFormatConfig();
        if (existing instanceof BlockBasedTableConfig blockBasedTableConfig) {
            return blockBasedTableConfig;
        }
        return new BlockBasedTableConfig();
    }

    @Override
    public void close(String storeName, Options options) {
        List<RocksObject> resources = storeResources.remove(storeName);
        if (resources != null) {
            resources.forEach(RocksObject::close);
            releaseSharedResources();
        }
    }

    private Cache sharedBlockCache(Map<String, Object> configs) {
        synchronized (SHARED_RESOURCE_LOCK) {
            if (sharedBlockCache == null) {
                sharedBlockCache = new LRUCache(getLong(configs, BLOCK_CACHE_SIZE_BYTES_CONFIG, 256L * 1024L * 1024L));
            }
            openStores++;
            return sharedBlockCache;
        }
    }

    private WriteBufferManager sharedWriteBufferManager(Map<String, Object> configs, Cache cache) {
        synchronized (SHARED_RESOURCE_LOCK) {
            if (sharedWriteBufferManager == null) {
                long size = getLong(configs, WRITE_BUFFER_MANAGER_SIZE_BYTES_CONFIG, 256L * 1024L * 1024L);
                sharedWriteBufferManager = new WriteBufferManager(size, cache, true);
            }
            return sharedWriteBufferManager;
        }
    }

    private void releaseSharedResources() {
        synchronized (SHARED_RESOURCE_LOCK) {
            openStores = Math.max(0, openStores - 1);
            if (openStores == 0) {
                if (sharedWriteBufferManager != null) {
                    sharedWriteBufferManager.close();
                    sharedWriteBufferManager = null;
                }
                if (sharedBlockCache != null) {
                    sharedBlockCache.close();
                    sharedBlockCache = null;
                }
            }
        }
    }

    private CompressionType compressionType(Map<String, Object> configs) {
        String configured = String.valueOf(configs.getOrDefault(COMPRESSION_TYPE_CONFIG, "LZ4_COMPRESSION"));
        return CompressionType.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }

    private long getLong(Map<String, Object> configs, String key, long defaultValue) {
        Object value = configs.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? defaultValue : Long.parseLong(value.toString());
    }

    private int getInt(Map<String, Object> configs, String key, int defaultValue) {
        Object value = configs.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    private double getDouble(Map<String, Object> configs, String key, double defaultValue) {
        Object value = configs.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? defaultValue : Double.parseDouble(value.toString());
    }

    private boolean getBoolean(Map<String, Object> configs, String key, boolean defaultValue) {
        Object value = configs.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }
}
