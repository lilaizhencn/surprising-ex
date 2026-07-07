package com.surprising.marketmaker.provider.service;

import com.surprising.product.api.ProductLine;
import java.time.Duration;

public interface MarketMakerLeaseCoordinator {

    boolean tryAcquire(ProductLine productLine, String strategyId, String symbol, String ownerId, Duration leaseDuration);
}
