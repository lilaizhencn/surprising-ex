package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import java.util.List;
import java.util.Optional;

public interface MarketMakerStrategyOverrideStore {

    List<StrategyConfigOverride> findAll();

    Optional<StrategyConfigOverride> find(String strategyId);

    StrategyConfigOverride save(StrategyConfigOverride override);

    void delete(String strategyId);
}
