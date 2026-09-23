package com.surprising.marketmaker.provider.config;

import lombok.Getter;
import lombok.Setter;

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

@Getter
@Validated
@ConfigurationProperties(prefix = "surprising.market-maker")
public class MarketMakerProperties {

    @Setter
    @Valid
    private Engine engine = new Engine();

    @Setter
    @Valid
    private Coordination coordination = new Coordination();

    @Setter
    @Valid
    private Quoting quoting = new Quoting();

    @Setter
    @Valid
    private Risk risk = new Risk();

    /** 启动时校验所有启用的行情源和策略都显式声明同一产品线。 */
    @PostConstruct
    void validateProductLineConfiguration() {
        for (ReferenceMarket.Source source : referenceMarket.sources) {
            if (source.enabled) {
                ProductLineConfiguration.require(source.productLine,
                        "market-maker.source." + source.name);
            }
        }
        for (Strategy strategy : strategies) {
            if (strategy.enabled) {
                ProductLineConfiguration.require(strategy.productLine,
                        "market-maker.strategy." + strategy.strategyId);
            }
        }
    }

    @Setter
    @Valid
    private Trade trade = new Trade();

    @Valid
    private ReferenceMarket referenceMarket = new ReferenceMarket();

    @Setter
    @Valid
    private List<Strategy> strategies = new ArrayList<>();

    @Valid
    private Kafka kafka = new Kafka();

    public void setReferenceMarket(ReferenceMarket referenceMarket) {
        this.referenceMarket = referenceMarket == null ? new ReferenceMarket() : referenceMarket;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
    }

    @Getter
    @Setter
    public static class Engine {
        private boolean enabled;
        @Min(50)
        @Max(1000)
        private long cycleDelayMs = 250L;
        private String nodeId;

    }

    /** 合约快照事件的消费配置。市场做市只在本地快照上读取合约规格。 */
    @Getter
    @Setter
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String instrumentSnapshotGroupId = "surprising-market-maker-instrument-snapshot-v1";

    }

    @Getter
    @Setter
    public static class Coordination {
        private boolean enabled = true;
        private Duration leaseDuration = Duration.ofSeconds(5);

    }

    @Getter
    @Setter
    public static class Quoting {
        @Min(1)
        @Max(200)
        private int orderBookDepth = 20;
        @Min(1)
        @Max(50)
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
        @Max(160)
        private int maxOrderOperationsPerCycle = 40;
        @Min(0)
        @Max(5_000_000)
        private long volatilitySpreadMultiplierPpm = 500_000L;
        @Positive
        private long maxVolatilitySpreadTicks = 100L;

    }

    @Getter
    @Setter
    public static class Risk {
        @Positive
        private long maxInventorySteps = 10_000L;
        @Min(0)
        @Max(1_000_000)
        private long maxInventorySkewPpm = 800_000L;

    }

    public static class Trade {
        @Getter
        @Setter
        private boolean enabled;
        @Size(max = 50)
        private List<@Positive Long> accountIds = new ArrayList<>();
        @Getter
        @Setter
        @Min(50)
        private long minIntervalMs = 250L;
        @Getter
        @Setter
        @Positive
        private long minQuantitySteps = 1L;
        @Getter
        @Setter
        @Positive
        private long maxQuantitySteps = 10L;
        @Getter
        @Setter
        @PositiveOrZero
        private long slippageTicks = 5L;
        @Getter
        @Setter
        @Min(1)
        @Max(20)
        private int maxSweepLevels = 1;
        @Getter
        @Setter
        @PositiveOrZero
        private long inventoryThresholdSteps = 5_000L;

        public List<Long> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(List<Long> accountIds) {
            this.accountIds = accountIds == null ? new ArrayList<>() : new ArrayList<>(accountIds);
        }

    }

    @Getter
    public static class ReferenceMarket {
        @Setter
        private boolean enabled;
        @Setter
        private boolean webSocketEnabled;
        @Setter
        private Duration refreshInterval = Duration.ofMillis(500);
        @Setter
        private Duration maxAge = Duration.ofSeconds(3);
        @Setter
        private Duration requestTimeout = Duration.ofSeconds(2);
        @Setter
        private Duration reconnectBackoff = Duration.ofSeconds(5);
        @Setter
        @Min(1)
        @Max(100)
        private int depthLevels = 20;
        @Setter
        @Min(1)
        @Max(1_000_000)
        private long quantityScalePpm = 1_000_000L;
        @Setter
        @Positive
        private long minQuantitySteps = 1L;
        @Setter
        @Positive
        private long maxQuantitySteps = 1_000L;
        @Size(max = 20)
        @Valid
        private List<Source> sources = new ArrayList<>();

        public void setSources(List<Source> sources) {
            this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        }

        @Getter
        public static class Source {
            @Setter
            private boolean enabled = true;
            private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String name;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String symbol;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String externalSymbol;
            @Setter
            @NotBlank
            @Size(max = 2048)
            private String url;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String parser;
            @Setter
            @Size(max = 2048)
            private String webSocketUrl;
            @Setter
            @Size(max = 2048)
            private String webSocketSubscribeMessage;
            @Setter
            @Size(max = 64)
            private String webSocketParser;

            public void setProductLine(ProductLine productLine) {
                this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
            }

        }
    }

    public static class Strategy {
        @Getter
        @Setter
        @NotBlank
        @Size(max = 64)
        private String strategyId;
        @Getter
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        @Getter
        @Setter
        private boolean enabled;
        @Size(min = 1)
        private List<@Positive Long> accountIds = new ArrayList<>();
        @Size(min = 1)
        private List<@NotBlank @Size(max = 64) String> symbols = new ArrayList<>();
        @Getter
        @Setter
        @Positive
        private long baseQuantitySteps = 1L;
        /**
         * 没有盘口、外部参考行情时使用的显式启动锚点。默认关闭，生产环境必须依赖实时行情；
         * 仅测试或刚上架且已由运营确认价格的策略可以显式配置。
         */
        @Getter
        @Setter
        @PositiveOrZero
        private long initialAnchorPriceTicks;
        private MarginMode marginMode = MarginMode.CROSS;
        @Getter
        @Setter
        @PositiveOrZero
        private long spreadTicks;
        @Getter
        @Setter
        @PositiveOrZero
        private long levelSpacingTicks;
        @Getter
        @Setter
        @PositiveOrZero
        private Long maxInventorySteps;
        @Getter
        @Setter
        @PositiveOrZero
        private Long maxInventorySkewPpm;
        @Getter
        @Setter
        @Min(0)
        @Max(50)
        private Integer orderLevels;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
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

        public MarginMode getMarginMode() {
            return MarginMode.defaultIfNull(marginMode);
        }

        public void setMarginMode(MarginMode marginMode) {
            this.marginMode = MarginMode.defaultIfNull(marginMode);
        }

    }
}
