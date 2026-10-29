package com.surprising.gateway.provider.config;

import com.surprising.websocket.provider.config.WebSocketProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
public class GatewayCorsConfig {
    @Bean("gatewayCorsSource")
    public CorsConfigurationSource gatewayCorsSource(GatewayProperties properties, WebSocketProperties websocket) {
        var rest = new CorsConfiguration();
        rest.setAllowedOrigins(properties.getSecurity().getCorsAllowedOrigins());
        rest.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        rest.setAllowedHeaders(List.of("*"));
        rest.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "X-Request-Id"));
        rest.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        for (String path : List.of("/api/**", "/fapi/**", "/dapi/**", "/eapi/**", "/sapi/**"))
            source.registerCorsConfiguration(path, rest);
        var ws = new CorsConfiguration();
        ws.setAllowedOriginPatterns(websocket.getSecurity().getAllowedOrigins());
        ws.setAllowedMethods(List.of("GET")); ws.setAllowedHeaders(List.of("*"));
        source.registerCorsConfiguration("/ws/v1", ws);
        return source;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> gatewayCorsFilter(@Qualifier("gatewayCorsSource") CorsConfigurationSource source) {
        var filter = new FilterRegistrationBean<>(new CorsFilter(source));
        filter.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        return filter;
    }
}
