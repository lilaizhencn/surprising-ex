package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责从本地不可变合约快照提供当前版本。
 */
@Repository
public class CandlestickInstrumentCurrentVersionRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final CandlestickProperties properties;

    /** 保留旧构造签名；合约当前版本由本地快照直接提供。 */
    public CandlestickInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public CandlestickInstrumentCurrentVersionRepository(InstrumentSnapshotCache snapshotCache,
                                                         CandlestickProperties properties) {
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
