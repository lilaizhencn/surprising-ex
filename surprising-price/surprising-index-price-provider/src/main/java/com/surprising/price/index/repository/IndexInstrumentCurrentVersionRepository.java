package com.surprising.price.index.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供当前版本。 */
@Repository
public class IndexInstrumentCurrentVersionRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final IndexPriceProperties properties;

    public IndexInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public IndexInstrumentCurrentVersionRepository(InstrumentSnapshotCache snapshotCache,
                                                  IndexPriceProperties properties) {
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

    private record CurrentVersion(String symbol, long version) {
    }
}
