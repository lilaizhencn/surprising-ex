package com.surprising.risk.api.model;

import java.util.List;

public record RiskPositionQueryResponse(
        int count,
        List<RiskPositionSnapshotResponse> positions) {
}
