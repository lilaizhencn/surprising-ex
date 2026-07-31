package com.surprising.trading.matching.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责从本地不可变合约快照提供当前版本。
 */
@Repository
public class MatchingInstrumentCurrentVersionRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final MatchingProperties properties;

    public MatchingInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public MatchingInstrumentCurrentVersionRepository(InstrumentSnapshotCache snapshotCache,
                                                     MatchingProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Map<String, Long> findAll() {
        if (snapshotCache == null || properties == null) {
            return Map.of();
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return Map.of();
        }
        return snapshotCache.current(productLine).stream()
                .collect(Collectors.toUnmodifiableMap(value -> value.symbol(), value -> value.version()));
    }

    public Optional<Long> findVersion(String symbol) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        return snapshotCache.current(properties.getKafka().getProductLine(), symbol)
                .map(value -> value.version());
    }

    private record CurrentVersion(String symbol, long version) {
    }
}
