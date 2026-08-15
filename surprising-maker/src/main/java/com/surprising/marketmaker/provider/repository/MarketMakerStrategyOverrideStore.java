package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Optional;

public interface MarketMakerStrategyOverrideStore {

    List<StrategyConfigOverride> findAll();

    Optional<StrategyConfigOverride> find(ProductLine productLine, String strategyId);

    StrategyConfigOverride save(StrategyConfigOverride override);

    void delete(ProductLine productLine, String strategyId);
}
