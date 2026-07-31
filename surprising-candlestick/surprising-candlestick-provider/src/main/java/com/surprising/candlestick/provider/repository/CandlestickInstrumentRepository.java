package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责从本地不可变合约快照提供行情所需的合约定义。
 */
@Repository
public class CandlestickInstrumentRepository {

    private final InstrumentSnapshotCache snapshotCache;
    private final CandlestickProperties properties;

    /** 保留旧构造签名，避免测试和旧调用方编译失败；运行时不再使用数据库。 */
    public CandlestickInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this(null, null);
    }

    @Autowired
    public CandlestickInstrumentRepository(InstrumentSnapshotCache snapshotCache,
                                           CandlestickProperties properties) {
        this.snapshotCache = snapshotCache;
        this.properties = properties;
    }

    public Optional<InstrumentDefinition> find(String symbol, long version) {
        return snapshot(symbol, version).map(CandlestickInstrumentRepository::toDefinition);
    }

    public List<InstrumentVersion> findEnabledPerpetualVersions() {
        return current().stream()
                .filter(value -> value.instrumentType() == com.surprising.instrument.api.model.InstrumentType.PERPETUAL)
                .filter(CandlestickInstrumentRepository::enabled)
                .map(value -> new InstrumentVersion(value.symbol(), value.version()))
                .toList();
    }

    public List<InstrumentVersion> findEnabledVersionsByContractType(String contractType) {
        return current().stream()
                .filter(value -> contractType == null
                        || value.contractType().productLine().contractTypeCode().equals(contractType))
                .filter(CandlestickInstrumentRepository::enabled)
                .map(value -> new InstrumentVersion(value.symbol(), value.version()))
                .toList();
    }

    private Optional<InstrumentResponse> snapshot(String symbol, long version) {
        if (snapshotCache == null || properties == null
                || !snapshotCache.initialized(properties.getKafka().getProductLine())) {
            return Optional.empty();
        }
        return snapshotCache.version(properties.getKafka().getProductLine(), symbol, version);
    }

    private List<InstrumentResponse> current() {
        if (snapshotCache == null || properties == null) {
            return List.of();
        }
        var productLine = properties.getKafka().getProductLine();
        return snapshotCache.initialized(productLine) ? snapshotCache.current(productLine) : List.of();
    }

    private static InstrumentDefinition toDefinition(InstrumentResponse value) {
        return new InstrumentDefinition(value.symbol(), value.version(), value.baseAsset(), value.quoteAsset(),
                value.priceTickUnits(), value.quantityStepUnits());
    }

    private static boolean enabled(InstrumentResponse value) {
        return value.status() == com.surprising.instrument.api.model.InstrumentStatus.PRE_TRADING
                || value.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING
                || value.status() == com.surprising.instrument.api.model.InstrumentStatus.HALT;
    }

    public record InstrumentDefinition(
            String symbol,
            long version,
            String baseAsset,
            String quoteAsset,
            long priceTickUnits,
            long quantityStepUnits) {
    }

    public record InstrumentVersion(String symbol, long version) {
    }
}
