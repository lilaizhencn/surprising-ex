package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.List;

/** 只读核对结果；期望值由已确认意图聚合，实际值来自SPOT权威账户查询，不保存派生余额。 */
public record C2cReconciliation(Status status,long pending,long failed,String reason,List<Asset> assets) {
    public enum Status { PENDING, REVIEW_REQUIRED, UNAVAILABLE, MATCHED, MISMATCH }
    public record Asset(String asset,BigDecimal expectedEscrow,long actualEscrow,long escrowLocked,
                         BigDecimal expectedFees,long actualFees,long feeLocked,boolean matches) { }
}
