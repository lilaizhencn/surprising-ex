package com.surprising.price.index.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责从本地不可变合约快照提供指数源配置。 */
@Repository
public class IndexInstrumentSourceRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final IndexPriceProperties properties;

    public IndexInstrumentSourceRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public IndexInstrumentSourceRepository(InstrumentSnapshotCache snapshotCache,
                                           IndexPriceProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Map<IndexInstrumentKey, List<IndexSource>> findEnabled(List<IndexInstrumentKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<IndexInstrumentKey, List<IndexSource>> grouped = new LinkedHashMap<>();
        keys.forEach(key -> grouped.put(key, new ArrayList<>()));
        if (snapshotCache == null || properties == null) {
            return grouped;
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return grouped;
        }
        for (IndexInstrumentKey key : keys) {
            snapshotCache.version(productLine, key.symbol(), key.version())
                    .ifPresent(instrument -> instrument.indexSources().stream()
                            .filter(com.surprising.instrument.api.model.IndexSourceConfig::enabled)
                            .map(this::toSource)
                            .forEach(source -> grouped.get(key).add(source)));
        }
        return grouped;
    }

    private IndexSource toSource(com.surprising.instrument.api.model.IndexSourceConfig row) {
        return new IndexSource(row.source(), row.enabled(), row.baseUrl(), row.path(), row.sourceSymbol(), row.parser(),
                row.quoteCurrency(), row.targetQuoteCurrency(), row.conversionBaseUrl(), row.conversionPath(),
                row.conversionParser(), row.conversionMode(), row.conversionOperation(),
                row.fallbackWeightMultiplierPpm(), row.websocketEnabled(), row.websocketUrl(),
                row.websocketSubscribeMessage(), row.websocketParser(), row.weightPpm());
    }

    public record IndexSource(
            String name,
            boolean enabled,
            String baseUrl,
            String path,
            String sourceSymbol,
            String parser,
            String quoteCurrency,
            String targetQuoteCurrency,
            String conversionBaseUrl,
            String conversionPath,
            String conversionParser,
            String conversionMode,
            String conversionOperation,
            long fallbackWeightMultiplierPpm,
            boolean websocketEnabled,
            String websocketUrl,
            String websocketSubscribeMessage,
            String websocketParser,
            long weightPpm) {
    }

    private record IndexSourceRow(IndexInstrumentKey key, IndexSource source) {
    }
}
