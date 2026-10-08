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
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
public class MarketMakerProperties {

    private volatile MarketMakerBusinessSettings businessSettings;

    public Engine getEngine() { return businessSettings == null ? engine : businessSettings.engine(); }
    public Quoting getQuoting() { return businessSettings == null ? quoting : businessSettings.quoting(); }
    public Risk getRisk() { return businessSettings == null ? risk : businessSettings.risk(); }
    public Trade getTrade() { return businessSettings == null ? trade : businessSettings.trade(); }
    public ReferenceMarket getReferenceMarket() { return businessSettings == null ? referenceMarket : businessSettings.referenceMarket(); }

    public void install(MarketMakerBusinessSettings settings) {
        if (settings == null || settings.engine() == null || settings.quoting() == null || settings.risk() == null
                || settings.trade() == null || settings.referenceMarket() == null)
            throw new IllegalArgumentException("complete maker business settings are required");
        settings.engine().setNodeId(getEngine().getNodeId());
        businessSettings = settings;
    }

    @Setter
    @jakarta.validation.constraints.NotNull
    private ProductLine productLine;

    @Setter
    @Valid
    private Engine engine = new Engine();

    @Setter
    @Valid
    private Quoting quoting = new Quoting();

    @Setter
    @Valid
    private Risk risk = new Risk();

    /** 启动时校验所有启用的行情源和策略都显式声明同一产品线。 */
    public void validateBusinessSettings() {
        var engine = getEngine();
        var referenceMarket = getReferenceMarket();
        if (engine.quoteWatchdogInterval == null || engine.tradeInterval == null
                || (engine.quoteWatchdogInterval.isNegative() || engine.quoteWatchdogInterval.toMillis() < 100) || engine.tradeInterval.isNegative())
            throw new IllegalArgumentException("maker trade interval must be non-negative and quote watchdog at least 100ms");
        for (ReferenceMarket.Source source : referenceMarket.sources) {
            if (source.enabled) {
                ProductLineConfiguration.requireSame(productLine, source.productLine,
                        "market-maker.source." + source.name);
            }
        }
        var identities = new java.util.HashSet<String>();
        for (Strategy strategy : strategies) {
            ProductLineConfiguration.requireSame(productLine, strategy.getProductLine(), "market-maker.strategy");
            if (!identities.add(strategy.getStrategyId().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("duplicate maker strategy ID");
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
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String nodeId;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration quoteWatchdogInterval = Duration.ofSeconds(1);
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration tradeInterval = Duration.ZERO;

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
        /** PMM relative refresh tolerance; combines with the minimum tick threshold. */
        @Min(0)
        @Max(100_000)
        private long refreshTolerancePpm = 0L;
        /** Permitted size drift before a resting quote is canceled and replaced. */
        @Min(0)
        @Max(900_000)
        private long quantityRefreshTolerancePpm = 0L;
        /** Stable per-level size variation, blended with the external depth. Zero keeps direct sizing. */
        @Min(0)
        @Max(900_000)
        private long quantityVariationPpm = 0L;
        /** PMM minimum distance from the external reference on each side. */
        @Min(0)
        @Max(100_000)
        private long halfSpreadPpm = 0L;
        /** Conservative linear maker fee when the account fee policy exceeds the instrument default. */
        @Min(0) @Max(999_999)
        private long makerFeeReservePpm;
        @Min(0) @Max(100_000)
        private long minNetHalfSpreadPpm;
        /** Maximum pressure/inventory quote skew; zero disables. Tick rounding bounds apply. */
        @Min(0) @Max(1000)
        private long referencePressureSkewPpm;
        @Min(0) @Max(1000)
        private long inventoryPriceSkewPpm;
        /** Quote-asset units; zero disables the deployment-specific depth target. */
        @PositiveOrZero
        private long linearLiquidityTargetNotionalUnits;
        @Min(1) @Max(100_000)
        private long liquiditySlippagePpm = 100;

        @Min(2)
        @Max(1000)
        private int maxOpenOrdersPerAccountSymbol = 30;
        @Min(1)
        @Max(100000)
        private long maxPriceDeviationPpm = 5000L;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration orderReconciliationInterval = Duration.ofMillis(500);
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
        /** Normal core batch size for simulated users; not a per-cycle operation quota. */
        @Getter
        @Setter
        @Min(1)
        @Max(20)
        private int ordersPerBatch = 1;

        @Getter
        @Setter
        private boolean enabled;
        @Size(max = 50)
        private List<@Positive Long> accountIds = new ArrayList<>();
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
        private boolean enabled = true;
        @Setter
        private boolean webSocketEnabled = true;
        @Setter
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration refreshInterval = Duration.ofMillis(500);
        @Setter
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration maxAge = Duration.ofSeconds(3);
        @Setter
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Duration requestTimeout = Duration.ofSeconds(2);
        @Setter
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
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
        @Size(max = 1000)
        @Valid
        private List<Source> sources = new ArrayList<>();

        public void setSources(List<Source> sources) {
            this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        }

        @Getter
        public static class Source {
            @Setter
            private boolean enabled = true;
            /** External contracts converted into our base-asset quantity; 1_000_000 means 1:1. */
            @Setter
            @Min(1)
            @Max(1_000_000)
            private long quantityScalePpm = 1_000_000L;
            private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String name;
            @Setter
            @NotBlank
            @Size(max = 64)
            private String instrumentId;
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
        private List<@NotBlank @Size(max = 64) String> instrumentIds = new ArrayList<>();
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

        public List<String> getInstrumentIds() {
            return instrumentIds;
        }

        public void setInstrumentIds(List<String> instrumentIds) {
            this.instrumentIds = instrumentIds;
        }

        public MarginMode getMarginMode() {
            return MarginMode.defaultIfNull(marginMode);
        }

        public void setMarginMode(MarginMode marginMode) {
            this.marginMode = MarginMode.defaultIfNull(marginMode);
        }

    }
}
