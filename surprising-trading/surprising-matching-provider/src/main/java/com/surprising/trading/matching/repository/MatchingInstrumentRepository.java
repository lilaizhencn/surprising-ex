package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责从本地不可变合约快照提供撮合合约定义。
 */
@Repository
public class MatchingInstrumentRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final MatchingProperties properties;

    public MatchingInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public MatchingInstrumentRepository(InstrumentSnapshotCache snapshotCache,
                                        MatchingProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public List<InstrumentVersion> findTradingVersions(String contractType) {
        if (snapshotCache == null || properties == null) {
            return List.of();
        }
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            return List.of();
        }
        return snapshotCache.current(productLine).stream()
                .filter(MatchingInstrumentRepository::trading)
                .filter(value -> contractType == null
                        || value.contractType().productLine().contractTypeCode().equals(contractType))
                .map(MatchingInstrumentRepository::toVersion)
                .toList();
    }

    public Optional<InstrumentVersion> findTrading(String symbol, long version, String contractType) {
        if (snapshotCache == null || properties == null) {
            return Optional.empty();
        }
        var productLine = properties.getKafka().getProductLine();
        return snapshotCache.version(productLine, symbol, version)
                .filter(MatchingInstrumentRepository::trading)
                .filter(value -> contractType == null
                        || value.contractType().productLine().contractTypeCode().equals(contractType))
                .map(MatchingInstrumentRepository::toVersion);
    }

    private static InstrumentVersion toVersion(com.surprising.instrument.api.model.InstrumentResponse value) {
        return new InstrumentVersion(value.symbol(), value.version(), value.baseAsset(), value.quoteAsset(),
                value.settleAsset());
    }

    private static boolean trading(com.surprising.instrument.api.model.InstrumentResponse value) {
        return value.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING
                || value.status() == com.surprising.instrument.api.model.InstrumentStatus.HALT;
    }

    public record InstrumentVersion(
            String symbol,
            long version,
            String baseAsset,
            String quoteAsset,
            String settleAsset) {

        public InstrumentSymbol toInstrumentSymbol() {
            return new InstrumentSymbol(symbol, baseAsset, quoteAsset, settleAsset);
        }
    }
}
