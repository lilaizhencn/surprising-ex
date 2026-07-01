package com.surprising.trading.api.model;

import java.time.Instant;
import java.util.List;

public record FeeTierRefreshResponse(
        int scannedUsers,
        int changedUsers,
        Instant refreshedAt,
        List<FeeTierAssignmentResponse> assignments) {
}
