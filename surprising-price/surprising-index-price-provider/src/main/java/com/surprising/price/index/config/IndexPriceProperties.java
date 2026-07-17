package com.surprising.price.index.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.price.index")
public class IndexPriceProperties {

    private Kafka kafka = new Kafka();
    private Calculation calculation = new Calculation();
    private Http http = new Http();
    private WebSocket webSocket = new WebSocket();
    private Fiat fiat = new Fiat();
    private Coordination coordination = new Coordination();
    private Audit audit = new Audit();
    private Instrument instrument = new Instrument();
    private List<SymbolConfig> symbols = new ArrayList<>();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Calculation getCalculation() {
        return calculation;
    }

    public void setCalculation(Calculation calculation) {
        this.calculation = calculation;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    public Fiat getFiat() {
        return fiat;
    }

    public void setFiat(Fiat fiat) {
        this.fiat = fiat;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public void setCoordination(Coordination coordination) {
        this.coordination = coordination;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public void setInstrument(Instrument instrument) {
        this.instrument = instrument;
    }

    public List<SymbolConfig> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<SymbolConfig> symbols) {
        this.symbols = symbols;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean productTopicsEnabled;
        private String indexPriceTopic = "surprising.perp.index.price.v1";
        private String groupId = "surprising-index-price-v1";
        private String cacheGroupId = "surprising-index-price-cache-local";
        private int concurrency = 2;
        private int maxPollRecords = 500;

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

        public String getIndexPriceTopic() {
            return productTopicsEnabled ? productTopics().indexPriceTopic() : indexPriceTopic;
        }

        public void setIndexPriceTopic(String indexPriceTopic) {
            this.indexPriceTopic = indexPriceTopic;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getCacheGroupId() {
            return cacheGroupId;
        }

        public void setCacheGroupId(String cacheGroupId) {
            this.cacheGroupId = cacheGroupId;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    public static class Calculation {
        private long pollDelayMs = 1000L;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration maxSourceAge = Duration.ofSeconds(5);
        private BigDecimal outlierThreshold = new BigDecimal("0.01");
        private int minValidSources = 3;
        private int scale = 18;
        private Duration conversionCacheTtl = Duration.ofSeconds(30);

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getMaxSourceAge() {
            return maxSourceAge;
        }

        public void setMaxSourceAge(Duration maxSourceAge) {
            this.maxSourceAge = maxSourceAge;
        }

        public BigDecimal getOutlierThreshold() {
            return outlierThreshold;
        }

        public void setOutlierThreshold(BigDecimal outlierThreshold) {
            this.outlierThreshold = outlierThreshold;
        }

        public int getMinValidSources() {
            return minValidSources;
        }

        public void setMinValidSources(int minValidSources) {
            this.minValidSources = minValidSources;
        }

        public int getScale() {
            return scale;
        }

        public void setScale(int scale) {
            this.scale = scale;
        }

        public Duration getConversionCacheTtl() {
            return conversionCacheTtl;
        }

        public void setConversionCacheTtl(Duration conversionCacheTtl) {
            this.conversionCacheTtl = conversionCacheTtl;
        }
    }

    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(3);
        private int maxConcurrentRequests = 32;
        private String userAgent = "surprising-index-price/1.0";

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    public static class WebSocket {
        private boolean enabled = true;
        private Duration idleTimeout = Duration.ofSeconds(20);
        private Duration reconnectInitialDelay = Duration.ofSeconds(1);
        private Duration reconnectMaxDelay = Duration.ofSeconds(30);
        private Duration healthCheckInterval = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getReconnectInitialDelay() {
            return reconnectInitialDelay;
        }

        public void setReconnectInitialDelay(Duration reconnectInitialDelay) {
            this.reconnectInitialDelay = reconnectInitialDelay;
        }

        public Duration getReconnectMaxDelay() {
            return reconnectMaxDelay;
        }

        public void setReconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
        }

        public Duration getHealthCheckInterval() {
            return healthCheckInterval;
        }

        public void setHealthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
        }
    }

    public static class Fiat {
        private boolean enabled = true;
        private long refreshDelayMs = 3600000L;
        private Duration staleAfter = Duration.ofHours(30);
        private String provider = "OPEN_ER_API";
        private String baseUrl = "https://open.er-api.com";
        private String path = "/v6/latest/{base}";
        private String baseCurrency = "USD";
        private List<String> quoteCurrencies = new ArrayList<>(List.of(
                "CNY", "EUR", "JPY", "KRW", "GBP", "AUD", "CAD", "HKD", "TWD", "SGD",
                "INR", "BRL", "TRY", "VND", "THB", "PHP", "IDR", "MYR"));
        private StableCoin stableCoin = new StableCoin();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getBaseCurrency() {
            return baseCurrency;
        }

        public void setBaseCurrency(String baseCurrency) {
            this.baseCurrency = baseCurrency;
        }

        public List<String> getQuoteCurrencies() {
            return quoteCurrencies;
        }

        public void setQuoteCurrencies(List<String> quoteCurrencies) {
            this.quoteCurrencies = quoteCurrencies;
        }

        public StableCoin getStableCoin() {
            return stableCoin;
        }

        public void setStableCoin(StableCoin stableCoin) {
            this.stableCoin = stableCoin;
        }
    }

    public static class StableCoin {
        private boolean enabled = true;
        private long refreshDelayMs = 10000L;
        private Duration staleAfter = Duration.ofMinutes(5);
        private String currency = "USDT";
        private String fiatCurrency = "USD";
        private String baseUrl = "https://api.exchange.coinbase.com";
        private String path = "/products/USDT-USD/ticker";
        private String parser = "COINBASE_TICKER";
        private BigDecimal fallbackRate = BigDecimal.ONE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public String getFiatCurrency() {
            return fiatCurrency;
        }

        public void setFiatCurrency(String fiatCurrency) {
            this.fiatCurrency = fiatCurrency;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getParser() {
            return parser;
        }

        public void setParser(String parser) {
            this.parser = parser;
        }

        public BigDecimal getFallbackRate() {
            return fallbackRate;
        }

        public void setFallbackRate(BigDecimal fallbackRate) {
            this.fallbackRate = fallbackRate;
        }
    }

    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }

    public static class Audit {
        private Duration retention = Duration.ofDays(3);
        private long cleanupDelayMs = Duration.ofMinutes(1).toMillis();
        private int cleanupBatchSize = 10_000;
        private int maxBatchesPerRun = 10;

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public long getCleanupDelayMs() {
            return cleanupDelayMs;
        }

        public void setCleanupDelayMs(long cleanupDelayMs) {
            this.cleanupDelayMs = cleanupDelayMs;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getMaxBatchesPerRun() {
            return maxBatchesPerRun;
        }

        public void setMaxBatchesPerRun(int maxBatchesPerRun) {
            this.maxBatchesPerRun = maxBatchesPerRun;
        }
    }

    public static class Instrument {
        private boolean enabled = true;
        private long refreshDelayMs = 30000L;
        private boolean fallbackToStaticSymbols = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }

        public boolean isFallbackToStaticSymbols() {
            return fallbackToStaticSymbols;
        }

        public void setFallbackToStaticSymbols(boolean fallbackToStaticSymbols) {
            this.fallbackToStaticSymbols = fallbackToStaticSymbols;
        }
    }

    public static class SymbolConfig {
        private String symbol;
        private int minValidSources = 0;
        private List<SourceConfig> sources = new ArrayList<>();

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public int getMinValidSources() {
            return minValidSources;
        }

        public void setMinValidSources(int minValidSources) {
            this.minValidSources = minValidSources;
        }

        public List<SourceConfig> getSources() {
            return sources;
        }

        public void setSources(List<SourceConfig> sources) {
            this.sources = sources;
        }
    }

    public static class SourceConfig {
        private String name;
        private boolean enabled = true;
        private String baseUrl;
        private String path;
        private String sourceSymbol;
        private String parser;
        private String quoteCurrency = "USDT";
        private String targetQuoteCurrency = "USDT";
        private String conversionBaseUrl;
        private String conversionPath;
        private String conversionParser;
        private String conversionMode = "DISCOUNT";
        private String conversionOperation = "MULTIPLY";
        private BigDecimal fallbackWeightMultiplier = new BigDecimal("0.50");
        private boolean websocketEnabled = true;
        private String websocketUrl;
        private String websocketSubscribeMessage;
        private String websocketParser;
        private BigDecimal weight = BigDecimal.ONE;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSourceSymbol() {
            return sourceSymbol;
        }

        public void setSourceSymbol(String sourceSymbol) {
            this.sourceSymbol = sourceSymbol;
        }

        public String getParser() {
            return parser;
        }

        public void setParser(String parser) {
            this.parser = parser;
        }

        public String getQuoteCurrency() {
            return quoteCurrency;
        }

        public void setQuoteCurrency(String quoteCurrency) {
            this.quoteCurrency = quoteCurrency;
        }

        public String getTargetQuoteCurrency() {
            return targetQuoteCurrency;
        }

        public void setTargetQuoteCurrency(String targetQuoteCurrency) {
            this.targetQuoteCurrency = targetQuoteCurrency;
        }

        public String getConversionBaseUrl() {
            return conversionBaseUrl;
        }

        public void setConversionBaseUrl(String conversionBaseUrl) {
            this.conversionBaseUrl = conversionBaseUrl;
        }

        public String getConversionPath() {
            return conversionPath;
        }

        public void setConversionPath(String conversionPath) {
            this.conversionPath = conversionPath;
        }

        public String getConversionParser() {
            return conversionParser;
        }

        public void setConversionParser(String conversionParser) {
            this.conversionParser = conversionParser;
        }

        public String getConversionMode() {
            return conversionMode;
        }

        public void setConversionMode(String conversionMode) {
            this.conversionMode = conversionMode;
        }

        public String getConversionOperation() {
            return conversionOperation;
        }

        public void setConversionOperation(String conversionOperation) {
            this.conversionOperation = conversionOperation;
        }

        public BigDecimal getFallbackWeightMultiplier() {
            return fallbackWeightMultiplier;
        }

        public void setFallbackWeightMultiplier(BigDecimal fallbackWeightMultiplier) {
            this.fallbackWeightMultiplier = fallbackWeightMultiplier;
        }

        public boolean isWebsocketEnabled() {
            return websocketEnabled;
        }

        public void setWebsocketEnabled(boolean websocketEnabled) {
            this.websocketEnabled = websocketEnabled;
        }

        public String getWebsocketUrl() {
            return websocketUrl;
        }

        public void setWebsocketUrl(String websocketUrl) {
            this.websocketUrl = websocketUrl;
        }

        public String getWebsocketSubscribeMessage() {
            return websocketSubscribeMessage;
        }

        public void setWebsocketSubscribeMessage(String websocketSubscribeMessage) {
            this.websocketSubscribeMessage = websocketSubscribeMessage;
        }

        public String getWebsocketParser() {
            return websocketParser;
        }

        public void setWebsocketParser(String websocketParser) {
            this.websocketParser = websocketParser;
        }

        public BigDecimal getWeight() {
            return weight;
        }

        public void setWeight(BigDecimal weight) {
            this.weight = weight;
        }
    }
}
