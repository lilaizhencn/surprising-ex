package com.surprising.trading.maintenance;

import com.surprising.product.api.ProductLine;

/** HTTP identifiers and quantities are strings to preserve all 64 bits in browsers. */
public record MaintenanceTask(String id, ProductLine productLine, MaintenanceRequest request,
                              String adminUserId, String status, String phase,
                              @com.fasterxml.jackson.annotation.JsonIgnore long cursorUserId,
                              @com.fasterxml.jackson.annotation.JsonIgnore int roundNo,
                              @com.fasterxml.jackson.annotation.JsonIgnore long step, String error,
                              String createdAt, String updatedAt) {
    public long taskId() { return Long.parseLong(id); }
    public long userId() { return Long.parseLong(request.userId()); }
    public long priceTicks() { return Long.parseLong(request.priceTicks()); }
}
