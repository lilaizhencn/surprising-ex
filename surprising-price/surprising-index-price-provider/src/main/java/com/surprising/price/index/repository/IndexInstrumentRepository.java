package com.surprising.price.index.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供指数价格合约定义。 */
@Repository
public class IndexInstrumentRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final IndexPriceProperties properties;

    public IndexInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public IndexInstrumentRepository(InstrumentSnapshotCache snapshotCache,
                                     IndexPriceProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public List<IndexInstrument> findTradingVersions(String contractType) {
        if (snapshotCache == null || properties == null) {
            return List.of();
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return List.of();
        }
        return snapshotCache.current(productLine).stream()
                .filter(value -> value.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING)
                .filter(value -> contractType == null
                        || value.contractType().productLine().contractTypeCode().equals(contractType))
                .map(value -> new IndexInstrument(value.symbol(), value.version(), value.minValidIndexSources()))
                .toList();
    }

    public record IndexInstrument(String symbol, long version, int minValidSources) {

        public IndexInstrumentKey key() {
            return new IndexInstrumentKey(symbol, version);
        }
    }
}
