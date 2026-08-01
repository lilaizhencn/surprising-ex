package com.surprising.liquidation.provider.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 从本地合约 JVM 快照批量读取强平默认费率。 */
@Service
public class LiquidationInstrumentSnapshotService {

    private final LiquidationProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public LiquidationInstrumentSnapshotService(LiquidationProperties properties,
                                                @org.springframework.beans.factory.annotation.Qualifier("liquidationInstrumentSnapshotCache")
                                                InstrumentSnapshotCache snapshotCache) {
        this.properties = properties == null ? new LiquidationProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    public Map<Long, InstrumentFee> findAll(List<InstrumentFeeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
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
