package com.surprising.trading.order.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentRuleRepository implements InstrumentRuleLookup {

    private final JdbcTemplate jdbcTemplate;
    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentRuleRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new TradingOrderProperties(), null);
    }

    public InstrumentRuleRepository(JdbcTemplate jdbcTemplate, TradingOrderProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public InstrumentRuleRepository(JdbcTemplate jdbcTemplate,
                                    TradingOrderProperties properties,
                                    InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @Override
    public Optional<InstrumentRule> currentRule(String symbol) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getKafka().getProductLine())) {
            throw new IllegalStateException("下单合约 JVM 快照尚未就绪");
        }
        return snapshotCache.current(properties.getKafka().getProductLine(), symbol).map(this::toRule);
    }

    private InstrumentRule toRule(InstrumentResponse value) {
        return new InstrumentRule(value.symbol(), value.version(), value.status().name(), value.instrumentType(),
                value.contractType(), value.baseAsset(), value.quoteAsset(), value.settleAsset(),
                value.supportedOrderTypes() == null ? Set.of() : Set.copyOf(value.supportedOrderTypes()),
                value.supportedTimeInForce() == null ? Set.of() : Set.copyOf(value.supportedTimeInForce()),
                value.marketOrderEnabled(), value.postOnlyEnabled(), value.reduceOnlyEnabled(),
                value.quantityStepUnits(), value.minQuantitySteps(), value.maxQuantitySteps(),
                value.minNotionalUnits(), value.maxNotionalUnits(), value.notionalMultiplierUnits(),
                value.maxLeveragePpm(), value.initialMarginRatePpm());
    }

    private Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

}
