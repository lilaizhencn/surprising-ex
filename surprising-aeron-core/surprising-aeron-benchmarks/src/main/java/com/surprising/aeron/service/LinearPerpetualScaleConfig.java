package com.surprising.aeron.service;

record LinearPerpetualScaleConfig(
        int listedSymbols,
        int activeSymbols,
        int maxPositionsPerUser,
        int maxOpenOrdersPerUser,
        LinearPerpetualTrafficProfile trafficProfile,
        boolean boundedSymbolWork) {

    static final int MAX_LISTED_SYMBOLS = 512;
    static final int MAX_POSITIONS_PER_USER = 20;
    static final int MAX_OPEN_ORDERS_PER_USER = 100;

    LinearPerpetualScaleConfig {
        if (listedSymbols < 2 || listedSymbols > MAX_LISTED_SYMBOLS) {
            throw new IllegalArgumentException("listedSymbols must be in [2," + MAX_LISTED_SYMBOLS + "]");
        }
        if (activeSymbols < 1 || activeSymbols > listedSymbols) {
            throw new IllegalArgumentException("activeSymbols must be in [1,listedSymbols]");
        }
        if (maxPositionsPerUser < 1
                || maxPositionsPerUser > Math.min(activeSymbols, MAX_POSITIONS_PER_USER)) {
            throw new IllegalArgumentException("maxPositionsPerUser exceeds the active symbol limit");
        }
        if (maxOpenOrdersPerUser < 0 || maxOpenOrdersPerUser > MAX_OPEN_ORDERS_PER_USER) {
            throw new IllegalArgumentException("maxOpenOrdersPerUser must be in [0,100]");
        }
        if (trafficProfile == null) throw new IllegalArgumentException("trafficProfile is required");
        if (trafficProfile == LinearPerpetualTrafficProfile.SINGLE_HOT && maxPositionsPerUser != 1) {
            throw new IllegalArgumentException("SINGLE_HOT requires maxPositionsPerUser=1");
        }
        if (trafficProfile == LinearPerpetualTrafficProfile.MOSTLY_IDLE && activeSymbols >= listedSymbols) {
            throw new IllegalArgumentException("MOSTLY_IDLE requires inactive listed symbols");
        }
    }

    static LinearPerpetualScaleConfig legacy(int symbols) {
        return new LinearPerpetualScaleConfig(symbols, symbols, 1, 3,
                LinearPerpetualTrafficProfile.UNIFORM, false);
    }

    static LinearPerpetualScaleConfig scale(int listedSymbols, int activeSymbols,
                                             int maxPositionsPerUser, int maxOpenOrdersPerUser,
                                             LinearPerpetualTrafficProfile trafficProfile) {
        return new LinearPerpetualScaleConfig(listedSymbols, activeSymbols, maxPositionsPerUser,
                maxOpenOrdersPerUser, trafficProfile, true);
    }
}
