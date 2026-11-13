package com.surprising.realtime.provider;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.realtime.provider.config.RealtimeRouterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration("realtimeProviderConfiguration")
@EnableConfigurationProperties({RealtimeRouterProperties.class, CandlestickProperties.class})
public class RealtimeApplication {
}
