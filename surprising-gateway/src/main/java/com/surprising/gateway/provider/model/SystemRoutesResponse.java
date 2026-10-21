package com.surprising.gateway.provider.model;

import java.time.Instant;
import java.util.List;

public record SystemRoutesResponse(
            Instant generatedAt,
            List<RouteResponse> publicRoutes,
            List<RouteResponse> adminRoutes) {
}
