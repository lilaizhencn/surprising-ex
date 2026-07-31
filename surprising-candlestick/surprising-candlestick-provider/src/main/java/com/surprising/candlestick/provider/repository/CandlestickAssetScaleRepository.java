package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责从本地不可变合约快照提供资产精度。
 */
@Repository
public class CandlestickAssetScaleRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final CandlestickProperties properties;

    public CandlestickAssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public CandlestickAssetScaleRepository(InstrumentSnapshotCache snapshotCache,
                                           CandlestickProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Optional<Long> findScaleUnits(String asset) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return Optional.empty();
        }
        return snapshotCache.scale(productLine, asset);
    }
}
