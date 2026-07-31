package com.surprising.price.mark.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供当前版本。 */
@Repository
public class MarkInstrumentCurrentVersionRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final MarkPriceProperties properties;

    public MarkInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public MarkInstrumentCurrentVersionRepository(InstrumentSnapshotCache snapshotCache,
                                                  MarkPriceProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Optional<Long> findVersion(String symbol) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        return snapshotCache.current(properties.getKafka().getProductLine(), symbol)
                .map(value -> value.version());
    }
}
