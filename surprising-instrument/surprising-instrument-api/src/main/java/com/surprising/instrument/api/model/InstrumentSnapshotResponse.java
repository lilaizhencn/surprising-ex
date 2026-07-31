package com.surprising.instrument.api.model;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;

/**
 * Instrument 启动初始化和缓存重建使用的完整快照。
 */
public record InstrumentSnapshotResponse(
        ProductLine productLine,
        long snapshotSequence,
        String checksum,
        List<InstrumentResponse> instruments,
        Map<String, Long> assetScales) {

    public InstrumentSnapshotResponse {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        instruments = instruments == null
                ? List.of()
                : instruments.stream().map(InstrumentResponse::immutableCopy).toList();
        checksum = checksum == null ? "" : checksum;
        assetScales = assetScales == null ? Map.of() : Map.copyOf(assetScales);
    }

    public InstrumentSnapshotResponse(ProductLine productLine,
                                      long snapshotSequence,
                                      String checksum,
                                      List<InstrumentResponse> instruments) {
        this(productLine, snapshotSequence, checksum, instruments, Map.of());
    }
}
