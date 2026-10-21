package com.surprising.gateway.provider.model;


public record RouteResponse(
            String routeType,
            String service,
            String baseUrl,
            String targetPrefix,
            boolean privateRoute,
            boolean basicAuthConfigured) {
}
