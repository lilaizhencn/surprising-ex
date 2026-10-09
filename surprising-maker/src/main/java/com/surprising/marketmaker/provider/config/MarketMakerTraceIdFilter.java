package com.surprising.marketmaker.provider.config;

import com.surprising.trading.api.http.HttpTraceFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarketMakerTraceIdFilter extends HttpTraceFilter {}
