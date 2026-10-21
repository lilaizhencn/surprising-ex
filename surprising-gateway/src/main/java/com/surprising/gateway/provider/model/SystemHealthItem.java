package com.surprising.gateway.provider.model;


public record SystemHealthItem(
            String routeType,
            String service,
            String baseUrl,
            String healthUrl,
            String targetPrefix,
            boolean privateRoute,
            boolean basicAuthConfigured,
            String status,
            Integer httpStatus,
            long latencyMs,
            String responseBody,
            String error) {
}
