package com.surprising.marketmaker.provider.service;

import java.time.Duration;

public interface MarketMakerLeaseCoordinator {

    boolean tryAcquire(String strategyId, String symbol, String ownerId, Duration leaseDuration);
}
