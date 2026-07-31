package com.surprising.liquidation.provider.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 合约默认费率仓储，只负责从本地不可变合约快照提供费率。 */
@Repository
public class LiquidationInstrumentFeeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public LiquidationInstrumentFeeRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LiquidationProperties(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LiquidationInstrumentFeeRepository(JdbcTemplate jdbcTemplate,
                                              LiquidationProperties properties,
                                              InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    public Map<Long, InstrumentFee> findAll(List<InstrumentFeeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("强平合约 JVM 快照尚未就绪");
        }
        return requests.stream()
                .map(request -> snapshotCache.version(productLine, request.symbol(), request.instrumentVersion())
                        .map(instrument -> new InstrumentFee(request.candidateId(), productLine,
                                instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm())))
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(InstrumentFee::candidateId, fee -> fee));
    }

    public record InstrumentFeeRequest(long candidateId, String symbol, long instrumentVersion) {
    }

    public record InstrumentFee(long candidateId,
                                ProductLine productLine,
                                long makerFeeRatePpm,
                                long takerFeeRatePpm) {
    }
}
