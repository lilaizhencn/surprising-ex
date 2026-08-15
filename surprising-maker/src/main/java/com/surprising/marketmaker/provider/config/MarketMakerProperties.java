package com.surprising.marketmaker.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.trading.api.model.MarginMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "surprising.market-maker")
public class MarketMakerProperties {

    @Valid
    private Engine engine = new Engine();

    @Valid
    private Coordination coordination = new Coordination();

    @Valid
    private Quoting quoting = new Quoting();

    @Valid
    private Risk risk = new Risk();

    /** 启动时校验所有启用的行情源和策略都显式声明同一产品线。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        for (ReferenceMarket.Source source : referenceMarket.sources) {
            if (source.enabled) {
                ProductLineConfiguration.require(source.productLine, true,
                        "market-maker.source." + source.name);
            }
        }
        for (Strategy strategy : strategies) {
            if (strategy.enabled) {
                ProductLineConfiguration.require(strategy.productLine, true,
                        "market-maker.strategy." + strategy.strategyId);
            }
        }
    }

    @Valid
    private Trade trade = new Trade();

    @Valid
    private ReferenceMarket referenceMarket = new ReferenceMarket();

    @Valid
    private List<Strategy> strategies = new ArrayList<>();

    @Valid
    private Kafka kafka = new Kafka();

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public void setCoordination(Coordination coordination) {
        this.coordination = coordination;
    }

    public Quoting getQuoting() {
        return quoting;
    }

    public void setQuoting(Quoting quoting) {
        this.quoting = quoting;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public Trade getTrade() {
        return trade;
    }

    public void setTrade(Trade trade) {
        this.trade = trade;
    }

    public ReferenceMarket getReferenceMarket() {
        return referenceMarket;
    }

    public void setReferenceMarket(ReferenceMarket referenceMarket) {
        this.referenceMarket = referenceMarket == null ? new ReferenceMarket() : referenceMarket;
    }

    public List<Strategy> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<Strategy> strategies) {
        this.strategies = strategies;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    public static class Engine {
        private boolean enabled;
        @Min(50)
        private long cycleDelayMs = 250L;
        private String nodeId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getCycleDelayMs() {
            return cycleDelayMs;
        }

        public void setCycleDelayMs(long cycleDelayMs) {
            this.cycleDelayMs = cycleDelayMs;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }
    }

    /** 合约快照事件的消费配置。市场做市只在本地快照上读取合约规格。 */
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String instrumentSnapshotGroupId = "surprising-market-maker-instrument-snapshot-v1";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getInstrumentSnapshotGroupId() {
            return instrumentSnapshotGroupId;
        }

        public void setInstrumentSnapshotGroupId(String instrumentSnapshotGroupId) {
            this.instrumentSnapshotGroupId = instrumentSnapshotGroupId;
        }
    }

    public static class Coordination {
        private boolean enabled = true;
        private Duration leaseDuration = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }

    public static class Quoting {
        @Min(1)
        @Max(200)
        private int orderBookDepth = 20;
        @Min(1)
        @Max(20)
        private int orderLevels = 3;
        @Positive
        private long minSpreadTicks = 10L;
        @Positive
        private long levelSpacingTicks = 10L;
        @PositiveOrZero
        private long refreshThresholdTicks = 2L;
        @Min(2)
        @Max(1000)
        private int maxOpenOrdersPerAccountSymbol = 30;
        private Duration staleOrderMaxAge = Duration.ofSeconds(30);
        @Min(1)
        @Max(100000)
        private long maxPriceDeviationPpm = 5000L;
        private Duration orderReconciliationInterval = Duration.ofMillis(500);
        @Min(1)
        @Max(500)
        private int maxOrderOperationsPerCycle = 40;
        @Min(0)
        @Max(5_000_000)
        private long volatilitySpreadMultiplierPpm = 500_000L;
        @Positive
        private long maxVolatilitySpreadTicks = 100L;

        public int getOrderBookDepth() {
            return orderBookDepth;
        }

        public void setOrderBookDepth(int orderBookDepth) {
            this.orderBookDepth = orderBookDepth;
        }

        public int getOrderLevels() {
            return orderLevels;
        }

        public void setOrderLevels(int orderLevels) {
            this.orderLevels = orderLevels;
        }

        public long getMinSpreadTicks() {
            return minSpreadTicks;
        }

        public void setMinSpreadTicks(long minSpreadTicks) {
            this.minSpreadTicks = minSpreadTicks;
        }

        public long getLevelSpacingTicks() {
            return levelSpacingTicks;
        }

        public void setLevelSpacingTicks(long levelSpacingTicks) {
            this.levelSpacingTicks = levelSpacingTicks;
        }

        public long getRefreshThresholdTicks() {
            return refreshThresholdTicks;
        }

        public void setRefreshThresholdTicks(long refreshThresholdTicks) {
            this.refreshThresholdTicks = refreshThresholdTicks;
        }

        public int getMaxOpenOrdersPerAccountSymbol() {
            return maxOpenOrdersPerAccountSymbol;
        }

        public void setMaxOpenOrdersPerAccountSymbol(int maxOpenOrdersPerAccountSymbol) {
            this.maxOpenOrdersPerAccountSymbol = maxOpenOrdersPerAccountSymbol;
        }

        public Duration getStaleOrderMaxAge() {
            return staleOrderMaxAge;
        }

        public void setStaleOrderMaxAge(Duration staleOrderMaxAge) {
            this.staleOrderMaxAge = staleOrderMaxAge;
        }

        public long getMaxPriceDeviationPpm() {
            return maxPriceDeviationPpm;
        }

        public void setMaxPriceDeviationPpm(long maxPriceDeviationPpm) {
            this.maxPriceDeviationPpm = maxPriceDeviationPpm;
        }

        public Duration getOrderReconciliationInterval() {
            return orderReconciliationInterval;
        }

        public void setOrderReconciliationInterval(Duration orderReconciliationInterval) {
            this.orderReconciliationInterval = orderReconciliationInterval;
        }

        public int getMaxOrderOperationsPerCycle() {
            return maxOrderOperationsPerCycle;
        }

        public void setMaxOrderOperationsPerCycle(int maxOrderOperationsPerCycle) {
            this.maxOrderOperationsPerCycle = maxOrderOperationsPerCycle;
        }

        public long getVolatilitySpreadMultiplierPpm() {
            return volatilitySpreadMultiplierPpm;
        }

        public void setVolatilitySpreadMultiplierPpm(long volatilitySpreadMultiplierPpm) {
            this.volatilitySpreadMultiplierPpm = volatilitySpreadMultiplierPpm;
        }

        public long getMaxVolatilitySpreadTicks() {
            return maxVolatilitySpreadTicks;
        }

        public void setMaxVolatilitySpreadTicks(long maxVolatilitySpreadTicks) {
            this.maxVolatilitySpreadTicks = maxVolatilitySpreadTicks;
        }
    }

    public static class Risk {
        @Positive
        private long maxInventorySteps = 10_000L;
        @Min(0)
        @Max(1_000_000)
        private long maxInventorySkewPpm = 800_000L;

        public long getMaxInventorySteps() {
            return maxInventorySteps;
        }

        public void setMaxInventorySteps(long maxInventorySteps) {
            this.maxInventorySteps = maxInventorySteps;
        }

        public long getMaxInventorySkewPpm() {
            return maxInventorySkewPpm;
        }

        public void setMaxInventorySkewPpm(long maxInventorySkewPpm) {
            this.maxInventorySkewPpm = maxInventorySkewPpm;
        }
    }

    public static class Trade {
        private boolean enabled;
        @Size(max = 50)
        private List<@Positive Long> accountIds = new ArrayList<>();
        @Min(50)
        private long minIntervalMs = 250L;
        @Positive
        private long minQuantitySteps = 1L;
        @Positive
        private long maxQuantitySteps = 10L;
        @PositiveOrZero
        private long slippageTicks = 5L;
        @Min(1)
        @Max(20)
        private int maxSweepLevels = 1;
        @PositiveOrZero
        private long inventoryThresholdSteps = 5_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Long> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(List<Long> accountIds) {
            this.accountIds = accountIds == null ? new ArrayList<>() : new ArrayList<>(accountIds);
        }

        public long getMinIntervalMs() {
            return minIntervalMs;
        }

        public void setMinIntervalMs(long minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        public long getMinQuantitySteps() {
            return minQuantitySteps;
        }

        public void setMinQuantitySteps(long minQuantitySteps) {
            this.minQuantitySteps = minQuantitySteps;
        }

        public long getMaxQuantitySteps() {
            return maxQuantitySteps;
        }

        public void setMaxQuantitySteps(long maxQuantitySteps) {
            this.maxQuantitySteps = maxQuantitySteps;
        }

        public long getSlippageTicks() {
            return slippageTicks;
        }

        public void setSlippageTicks(long slippageTicks) {
            this.slippageTicks = slippageTicks;
        }

        public int getMaxSweepLevels() {
            return maxSweepLevels;
        }

        public void setMaxSweepLevels(int maxSweepLevels) {
            this.maxSweepLevels = maxSweepLevels;
        }

        public long getInventoryThresholdSteps() {
            return inventoryThresholdSteps;
        }

        public void setInventoryThresholdSteps(long inventoryThresholdSteps) {
            this.inventoryThresholdSteps = inventoryThresholdSteps;
        }
    }

    public static class ReferenceMarket {
        private boolean enabled;
        private boolean webSocketEnabled;
        private Duration refreshInterval = Duration.ofMillis(500);
        private Duration maxAge = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(2);
        private Duration reconnectBackoff = Duration.ofSeconds(5);
        @Min(1)
        @Max(100)
        private int depthLevels = 20;
        @Min(1)
        @Max(1_000_000)
        private long quantityScalePpm = 1_000_000L;
        @Positive
        private long minQuantitySteps = 1L;
        @Positive
        private long maxQuantitySteps = 1_000L;
        @Size(max = 20)
        @Valid
        private List<Source> sources = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isWebSocketEnabled() {
            return webSocketEnabled;
        }

        public void setWebSocketEnabled(boolean webSocketEnabled) {
            this.webSocketEnabled = webSocketEnabled;
        }

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getReconnectBackoff() {
            return reconnectBackoff;
        }

        public void setReconnectBackoff(Duration reconnectBackoff) {
            this.reconnectBackoff = reconnectBackoff;
        }

        public int getDepthLevels() {
            return depthLevels;
        }

        public void setDepthLevels(int depthLevels) {
            this.depthLevels = depthLevels;
        }

        public long getQuantityScalePpm() {
            return quantityScalePpm;
        }

        public void setQuantityScalePpm(long quantityScalePpm) {
            this.quantityScalePpm = quantityScalePpm;
        }

        public long getMinQuantitySteps() {
            return minQuantitySteps;
        }

        public void setMinQuantitySteps(long minQuantitySteps) {
            this.minQuantitySteps = minQuantitySteps;
        }

        public long getMaxQuantitySteps() {
            return maxQuantitySteps;
        }

        public void setMaxQuantitySteps(long maxQuantitySteps) {
            this.maxQuantitySteps = maxQuantitySteps;
        }

        public List<Source> getSources() {
            return sources;
        }

        public void setSources(List<Source> sources) {
            this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        }

        public static class Source {
            private boolean enabled = true;
            private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
            @NotBlank
            @Size(max = 64)
            private String name;
            @NotBlank
            @Size(max = 64)
            private String symbol;
            @NotBlank
            @Size(max = 64)
            private String externalSymbol;
            @NotBlank
            @Size(max = 2048)
            private String url;
            @NotBlank
            @Size(max = 64)
            private String parser;
            @Size(max = 2048)
            private String webSocketUrl;
            @Size(max = 2048)
            private String webSocketSubscribeMessage;
            @Size(max = 64)
            private String webSocketParser;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public ProductLine getProductLine() {
                return productLine;
            }

            public void setProductLine(ProductLine productLine) {
                this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getSymbol() {
                return symbol;
            }

            public void setSymbol(String symbol) {
                this.symbol = symbol;
            }

            public String getExternalSymbol() {
                return externalSymbol;
            }

            public void setExternalSymbol(String externalSymbol) {
                this.externalSymbol = externalSymbol;
            }

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getParser() {
                return parser;
            }

            public void setParser(String parser) {
                this.parser = parser;
            }

            public String getWebSocketUrl() {
                return webSocketUrl;
            }

            public void setWebSocketUrl(String webSocketUrl) {
                this.webSocketUrl = webSocketUrl;
            }

            public String getWebSocketSubscribeMessage() {
                return webSocketSubscribeMessage;
            }

            public void setWebSocketSubscribeMessage(String webSocketSubscribeMessage) {
                this.webSocketSubscribeMessage = webSocketSubscribeMessage;
            }

            public String getWebSocketParser() {
                return webSocketParser;
            }

            public void setWebSocketParser(String webSocketParser) {
                this.webSocketParser = webSocketParser;
            }
        }
    }

    public static class Strategy {
        @NotBlank
        @Size(max = 64)
        private String strategyId;
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        private boolean enabled;
        @Size(min = 1)
        private List<@Positive Long> accountIds = new ArrayList<>();
        @Size(min = 1)
        private List<@NotBlank @Size(max = 64) String> symbols = new ArrayList<>();
        @Positive
        private long baseQuantitySteps = 1L;
        /**
         * 没有盘口、外部参考行情时使用的显式启动锚点。默认关闭，生产环境必须依赖实时行情；
         * 仅测试或刚上架且已由运营确认价格的策略可以显式配置。
         */
        @PositiveOrZero
        private long initialAnchorPriceTicks;
        private MarginMode marginMode = MarginMode.CROSS;
        @PositiveOrZero
        private long spreadTicks;
        @PositiveOrZero
        private long levelSpacingTicks;
        @PositiveOrZero
        private Long maxInventorySteps;
        @PositiveOrZero
        private Long maxInventorySkewPpm;
        @Min(0)
        @Max(20)
        private Integer orderLevels;

        public String getStrategyId() {
            return strategyId;
        }

        public void setStrategyId(String strategyId) {
            this.strategyId = strategyId;
        }

        public ProductLine getProductLine() {
            return productLine;
        }

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Long> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(List<Long> accountIds) {
            this.accountIds = accountIds;
        }

        public List<String> getSymbols() {
            return symbols;
        }

        public void setSymbols(List<String> symbols) {
            this.symbols = symbols;
        }

        public long getBaseQuantitySteps() {
            return baseQuantitySteps;
        }

        public void setBaseQuantitySteps(long baseQuantitySteps) {
            this.baseQuantitySteps = baseQuantitySteps;
        }

        public long getInitialAnchorPriceTicks() {
            return initialAnchorPriceTicks;
        }

        public void setInitialAnchorPriceTicks(long initialAnchorPriceTicks) {
            this.initialAnchorPriceTicks = initialAnchorPriceTicks;
        }

        public MarginMode getMarginMode() {
            return MarginMode.defaultIfNull(marginMode);
        }

        public void setMarginMode(MarginMode marginMode) {
            this.marginMode = MarginMode.defaultIfNull(marginMode);
        }

        public long getSpreadTicks() {
            return spreadTicks;
        }

        public void setSpreadTicks(long spreadTicks) {
            this.spreadTicks = spreadTicks;
        }

        public long getLevelSpacingTicks() {
            return levelSpacingTicks;
        }

        public void setLevelSpacingTicks(long levelSpacingTicks) {
            this.levelSpacingTicks = levelSpacingTicks;
        }

        public Long getMaxInventorySteps() {
            return maxInventorySteps;
        }

        public void setMaxInventorySteps(Long maxInventorySteps) {
            this.maxInventorySteps = maxInventorySteps;
        }

        public Long getMaxInventorySkewPpm() {
            return maxInventorySkewPpm;
        }

        public void setMaxInventorySkewPpm(Long maxInventorySkewPpm) {
            this.maxInventorySkewPpm = maxInventorySkewPpm;
        }

        public Integer getOrderLevels() {
            return orderLevels;
        }

        public void setOrderLevels(Integer orderLevels) {
            this.orderLevels = orderLevels;
        }
    }
}
