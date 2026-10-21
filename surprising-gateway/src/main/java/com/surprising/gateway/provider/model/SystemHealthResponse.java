package com.surprising.gateway.provider.model;

import java.time.Instant;
import java.util.List;

public record SystemHealthResponse(
            Instant generatedAt,
            int count,
            long up,
            long down,
            long unknown,
            List<SystemHealthItem> services) {
}
