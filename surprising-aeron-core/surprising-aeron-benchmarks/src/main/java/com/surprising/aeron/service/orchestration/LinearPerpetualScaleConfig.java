package com.surprising.aeron.service.orchestration;

public record LinearPerpetualScaleConfig(
        int listedSymbols,
        int activeSymbols,
        int maxPositionsPerUser,
        int maxOpenOrdersPerUser,
        LinearPerpetualTrafficProfile trafficProfile,
        int lifecycleSymbolsPerRun,
        boolean boundedSymbolWork) {

    public static final int MAX_LISTED_SYMBOLS = 512;
    public static final int MAX_POSITIONS_PER_USER = 20;
    public static final int MAX_OPEN_ORDERS_PER_USER = 100;

    public LinearPerpetualScaleConfig {
        if (listedSymbols < 2 || listedSymbols > MAX_LISTED_SYMBOLS) {
            throw new IllegalArgumentException("listedSymbols must be in [2," + MAX_LISTED_SYMBOLS + "]");
        }
        if (activeSymbols < 1 || activeSymbols > listedSymbols) {
            throw new IllegalArgumentException("activeSymbols must be in [1,listedSymbols]");
        }
        if (maxPositionsPerUser < 1
                || maxPositionsPerUser > Math.min(activeSymbols, MAX_POSITIONS_PER_USER)) {
            throw new IllegalArgumentException("maxPositionsPerUser exceeds the active instrumentId limit");
        }
        if (maxOpenOrdersPerUser < 0 || maxOpenOrdersPerUser > MAX_OPEN_ORDERS_PER_USER) {
            throw new IllegalArgumentException("maxOpenOrdersPerUser must be in [0,100]");
        }
        if (trafficProfile == null) throw new IllegalArgumentException("trafficProfile is required");
        if (lifecycleSymbolsPerRun < 1 || lifecycleSymbolsPerRun > activeSymbols) {
            throw new IllegalArgumentException("lifecycleSymbolsPerRun must be in [1,activeSymbols]");
        }
        if (trafficProfile == LinearPerpetualTrafficProfile.SINGLE_HOT && maxPositionsPerUser != 1) {
            throw new IllegalArgumentException("SINGLE_HOT requires maxPositionsPerUser=1");
        }
        if (trafficProfile == LinearPerpetualTrafficProfile.MOSTLY_IDLE && activeSymbols >= listedSymbols) {
            throw new IllegalArgumentException("MOSTLY_IDLE requires inactive listed symbols");
        }
    }

    public static LinearPerpetualScaleConfig production(int symbols) {
        return new LinearPerpetualScaleConfig(symbols, symbols, 1, 3,
                LinearPerpetualTrafficProfile.UNIFORM, symbols, false);
    }

    public static LinearPerpetualScaleConfig scale(int listedSymbols, int activeSymbols,
                                             int maxPositionsPerUser, int maxOpenOrdersPerUser,
                                             LinearPerpetualTrafficProfile trafficProfile,
                                             int lifecycleSymbolsPerRun) {
        return new LinearPerpetualScaleConfig(listedSymbols, activeSymbols, maxPositionsPerUser,
                maxOpenOrdersPerUser, trafficProfile, lifecycleSymbolsPerRun, true);
    }
}
