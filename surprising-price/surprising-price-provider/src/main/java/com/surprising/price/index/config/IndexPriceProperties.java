package com.surprising.price.index.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.math.BigDecimal;
import java.time.Duration;
import java.net.ProxySelector;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@ConfigurationProperties(prefix = "surprising.price.index")
public class IndexPriceProperties {

    @Setter
    private Kafka kafka = new Kafka();
    @Setter
    private Calculation calculation = new Calculation();
    @Setter
    private Http http = new Http();
    @Setter
    private WebSocket webSocket = new WebSocket();
    @Setter
    private Fiat fiat = new Fiat();
    @Setter
    private Coordination coordination = new Coordination();
    @Setter
    private Audit audit = new Audit();
    private List<String> requiredSymbols = List.of();

    public void setRequiredSymbols(List<String> requiredSymbols) {
        this.requiredSymbols = requiredSymbols == null ? List.of() : requiredSymbols.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        @Setter
        private String groupId = "surprising-index-price-v1";
        @Setter
        private String cacheGroupId = "surprising-index-price-cache-local";
        @Setter
        private int concurrency = 2;
        @Setter
        private int maxPollRecords = 500;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getPriceEventsTopic() {
            return productTopics().priceEventsTopic();
        }

        public String getInstrumentSnapshotGroupId() {
            return "surprising-" + productLine.topicSegment() + "-index-instrument-snapshot-v1";
        }

        private ProductTopicNames productTopics() {
            return ProductTopicNames.of(productLine);
        }
    }

    @Getter
    @Setter
    public static class Calculation {
        @Min(50)
        @Max(1000)
        private long pollDelayMs = 1000L;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration maxSourceAge = Duration.ofSeconds(5);
        private BigDecimal outlierThreshold = new BigDecimal("0.01");
        @Min(3)
        @Max(20)
        private int minValidSources = 3;
        private int scale = 18;
        private Duration conversionCacheTtl = Duration.ofSeconds(30);

    }

    @Getter
    @Setter
    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(3);
        private int maxConcurrentRequests = 32;
        private String userAgent = "surprising-index-price/1.0";
        private boolean proxyEnabled;
        private String proxyHost = "127.0.0.1";
        private int proxyPort = 7897;

        public ProxySelector proxySelector() {
            if (!proxyEnabled) {
                return ProxySelector.getDefault();
            }
            if (proxyHost == null || proxyHost.isBlank() || proxyPort < 1 || proxyPort > 65535) {
                throw new IllegalStateException("invalid external HTTP proxy configuration");
            }
            return ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort));
        }
    }

    @Getter
    @Setter
    public static class WebSocket {
        private boolean enabled = true;
        private boolean restFallbackEnabled;
        private long refreshDelayMs = 30000L;
        private Duration idleTimeout = Duration.ofSeconds(20);
        private Duration reconnectInitialDelay = Duration.ofSeconds(1);
        private Duration reconnectMaxDelay = Duration.ofSeconds(30);
        private Duration healthCheckInterval = Duration.ofSeconds(5);

    }

    @Getter
    @Setter
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

    }

    @Getter
    @Setter
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

    }

    @Getter
    @Setter
    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

    }

    @Getter
    @Setter
    public static class Audit {
        private Duration retention = Duration.ofDays(3);
        private long cleanupDelayMs = Duration.ofMinutes(1).toMillis();
        private int cleanupBatchSize = 10_000;
        private int maxBatchesPerRun = 10;

    }

    @Getter
    @Setter
    public static class SymbolConfig {
        private String symbol;
        private int minValidSources = 0;
        private List<SourceConfig> sources = new ArrayList<>();

    }

    @Getter
    @Setter
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

    }
}
