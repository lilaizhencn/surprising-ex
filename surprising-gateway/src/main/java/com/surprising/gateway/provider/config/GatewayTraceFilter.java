package com.surprising.gateway.provider.config;

import com.surprising.trading.api.http.HttpTraceFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Trace the request before authentication, retaining the gateway's existing attribute contract. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayTraceFilter extends HttpTraceFilter {
    public static final String TRACE_ID_ATTRIBUTE = HttpTraceFilter.TRACE_ID_ATTRIBUTE;
}
