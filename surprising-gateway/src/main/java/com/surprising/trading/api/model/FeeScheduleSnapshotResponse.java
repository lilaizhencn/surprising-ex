package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.List;

/** 费率模块提供给其他模块初始化 JVM 快照的完整响应。 */
public record FeeScheduleSnapshotResponse(
        ProductLine productLine,
        long snapshotSequence,
        String checksum,
        List<FeeScheduleResponse> schedules) {

    public FeeScheduleSnapshotResponse {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        schedules = schedules == null ? List.of() : schedules.stream().filter(java.util.Objects::nonNull).toList();
        checksum = checksum == null ? "" : checksum;
    }
}
