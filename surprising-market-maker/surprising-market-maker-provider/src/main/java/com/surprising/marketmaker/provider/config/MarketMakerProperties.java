package com.surprising.marketmaker.provider.config;

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

    @Valid
    private Trade trade = new Trade();

    @Valid
    private List<Strategy> strategies = new ArrayList<>();

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

    public List<Strategy> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<Strategy> strategies) {
        this.strategies = strategies;
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

        public long getInventoryThresholdSteps() {
            return inventoryThresholdSteps;
        }

        public void setInventoryThresholdSteps(long inventoryThresholdSteps) {
            this.inventoryThresholdSteps = inventoryThresholdSteps;
        }
    }

    public static class Strategy {
        @NotBlank
        @Size(max = 64)
        private String strategyId;
        private boolean enabled;
        @Size(min = 1)
        private List<@Positive Long> accountIds = new ArrayList<>();
        @Size(min = 1)
        private List<@NotBlank @Size(max = 64) String> symbols = new ArrayList<>();
        @Positive
        private long baseQuantitySteps = 1L;
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
