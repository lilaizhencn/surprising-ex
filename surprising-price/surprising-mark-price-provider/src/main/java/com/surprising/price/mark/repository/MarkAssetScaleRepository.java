package com.surprising.price.mark.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供资产精度。 */
@Repository
public class MarkAssetScaleRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final MarkPriceProperties properties;

    public MarkAssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public MarkAssetScaleRepository(InstrumentSnapshotCache snapshotCache,
                                    MarkPriceProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public OptionalLong findScaleUnits(String asset) {
        if (snapshotCache == null || properties == null) {
            return OptionalLong.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return OptionalLong.empty();
        }
        return snapshotCache.scale(productLine, asset).map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }
}
