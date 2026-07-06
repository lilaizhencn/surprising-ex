package com.surprising.candlestick.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "surprising.candlestick")
public class CandlestickProperties {

    private List<String> periods = new ArrayList<>(List.of("1m", "5m", "15m", "30m", "1h", "4h", "1d"));
    private Kafka kafka = new Kafka();
    private Stream stream = new Stream();
    private Flush flush = new Flush();
    private Query query = new Query();
    private Symbols symbols = new Symbols();
    private Rocksdb rocksdb = new Rocksdb();

    public List<String> getPeriods() {
        return periods;
    }

    public void setPeriods(List<String> periods) {
        this.periods = periods;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream;
    }

    public Flush getFlush() {
        return flush;
    }

    public void setFlush(Flush flush) {
        this.flush = flush;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Symbols getSymbols() {
        return symbols;
    }

    public void setSymbols(Symbols symbols) {
        this.symbols = symbols;
    }

    public Rocksdb getRocksdb() {
        return rocksdb;
    }

    public void setRocksdb(Rocksdb rocksdb) {
        this.rocksdb = rocksdb;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String tradeTopic = "surprising.perp.match.trades.v1";
        private String candleTopic = "surprising.perp.candle.events.v1";
        private String applicationId = "surprising-candlestick-v1";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public boolean isProductTopicsEnabled() {
            return productTopicsEnabled;
        }

        public void setProductTopicsEnabled(boolean productTopicsEnabled) {
            this.productTopicsEnabled = productTopicsEnabled;
        }

        public String getTradeTopic() {
            return productTopicsEnabled ? productTopics().matchTradesTopic() : tradeTopic;
        }

        public void setTradeTopic(String tradeTopic) {
            this.tradeTopic = tradeTopic;
        }

        public String getCandleTopic() {
            return productTopicsEnabled ? productTopics().candleEventsTopic() : candleTopic;
        }

        public void setCandleTopic(String candleTopic) {
            this.candleTopic = candleTopic;
        }

        public String getApplicationId() {
            return productTopicsEnabled ? productTopics().consumerGroup("candlestick") : applicationId;
        }

        public void setApplicationId(String applicationId) {
            this.applicationId = applicationId;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Stream {
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        private String stateDir = "data/kafka-streams";
        private Duration commitInterval = Duration.ofSeconds(1);
        private Duration dedupeRetention = Duration.ofDays(3);
        private int dedupeCleanupMaxEntries = 10000;

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public String getStateDir() {
            return stateDir;
        }

        public void setStateDir(String stateDir) {
            this.stateDir = stateDir;
        }

        public Duration getCommitInterval() {
            return commitInterval;
        }

        public void setCommitInterval(Duration commitInterval) {
            this.commitInterval = commitInterval;
        }

        public Duration getDedupeRetention() {
            return dedupeRetention;
        }

        public void setDedupeRetention(Duration dedupeRetention) {
            this.dedupeRetention = dedupeRetention;
        }

        public int getDedupeCleanupMaxEntries() {
            return dedupeCleanupMaxEntries;
        }

        public void setDedupeCleanupMaxEntries(int dedupeCleanupMaxEntries) {
            this.dedupeCleanupMaxEntries = dedupeCleanupMaxEntries;
        }
    }

    public static class Flush {
        private Duration interval = Duration.ofSeconds(1);
        private int maxBatchSize = 1000;

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }
    }

    public static class Query {
        private int maxLimit = 1500;

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }
    }

    public static class Symbols {
        private boolean acceptUnknownSymbols = true;
        private String source = "INSTRUMENT";
        private long refreshDelayMs = 30000L;

        public boolean isAcceptUnknownSymbols() {
            return acceptUnknownSymbols;
        }

        public void setAcceptUnknownSymbols(boolean acceptUnknownSymbols) {
            this.acceptUnknownSymbols = acceptUnknownSymbols;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }
    }

    public static class Rocksdb {
        private DataSize blockCacheSize = DataSize.ofMegabytes(256);
        private DataSize writeBufferSize = DataSize.ofMegabytes(64);
        private DataSize writeBufferManagerSize = DataSize.ofMegabytes(256);
        private int maxWriteBufferNumber = 3;
        private DataSize blockSize = DataSize.ofKilobytes(16);
        private int maxBackgroundJobs = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        private DataSize targetFileSizeBase = DataSize.ofMegabytes(64);
        private DataSize bytesPerSync = DataSize.ofMegabytes(1);
        private String compressionType = "LZ4_COMPRESSION";
        private boolean bloomFilterEnabled = true;
        private double bloomFilterBitsPerKey = 10.0d;

        public DataSize getBlockCacheSize() {
            return blockCacheSize;
        }

        public void setBlockCacheSize(DataSize blockCacheSize) {
            this.blockCacheSize = blockCacheSize;
        }

        public DataSize getWriteBufferSize() {
            return writeBufferSize;
        }

        public void setWriteBufferSize(DataSize writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
        }

        public DataSize getWriteBufferManagerSize() {
            return writeBufferManagerSize;
        }

        public void setWriteBufferManagerSize(DataSize writeBufferManagerSize) {
            this.writeBufferManagerSize = writeBufferManagerSize;
        }

        public int getMaxWriteBufferNumber() {
            return maxWriteBufferNumber;
        }

        public void setMaxWriteBufferNumber(int maxWriteBufferNumber) {
            this.maxWriteBufferNumber = maxWriteBufferNumber;
        }

        public DataSize getBlockSize() {
            return blockSize;
        }

        public void setBlockSize(DataSize blockSize) {
            this.blockSize = blockSize;
        }

        public int getMaxBackgroundJobs() {
            return maxBackgroundJobs;
        }

        public void setMaxBackgroundJobs(int maxBackgroundJobs) {
            this.maxBackgroundJobs = maxBackgroundJobs;
        }

        public DataSize getTargetFileSizeBase() {
            return targetFileSizeBase;
        }

        public void setTargetFileSizeBase(DataSize targetFileSizeBase) {
            this.targetFileSizeBase = targetFileSizeBase;
        }

        public DataSize getBytesPerSync() {
            return bytesPerSync;
        }

        public void setBytesPerSync(DataSize bytesPerSync) {
            this.bytesPerSync = bytesPerSync;
        }

        public String getCompressionType() {
            return compressionType;
        }

        public void setCompressionType(String compressionType) {
            this.compressionType = compressionType;
        }

        public boolean isBloomFilterEnabled() {
            return bloomFilterEnabled;
        }

        public void setBloomFilterEnabled(boolean bloomFilterEnabled) {
            this.bloomFilterEnabled = bloomFilterEnabled;
        }

        public double getBloomFilterBitsPerKey() {
            return bloomFilterBitsPerKey;
        }

        public void setBloomFilterBitsPerKey(double bloomFilterBitsPerKey) {
            this.bloomFilterBitsPerKey = bloomFilterBitsPerKey;
        }
    }
}
