package com.surprising.price.mark.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供标记价所需的合约定义。 */
@Repository
public class MarkInstrumentRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final MarkPriceProperties properties;

    public MarkInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public MarkInstrumentRepository(InstrumentSnapshotCache snapshotCache,
                                    MarkPriceProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Optional<MarkInstrument> find(String symbol, long version, String contractType) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        return snapshotCache.version(productLine, symbol, version)
                .filter(value -> contractType == null
                        || value.contractType().productLine().contractTypeCode().equals(contractType))
                .map(value -> new MarkInstrument(value.symbol(), value.version(), value.quoteAsset(),
                        value.priceTickUnits()));
    }

    public record MarkInstrument(String symbol, long version, String quoteAsset, long priceTickUnits) {
    }
}
