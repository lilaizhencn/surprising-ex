package com.surprising.realtime.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@org.springframework.cloud.openfeign.EnableFeignClients(clients = com.surprising.instrument.api.client.InstrumentRpcApi.class)
@org.springframework.kafka.annotation.EnableKafka
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.surprising.realtime.provider", "com.surprising.candlestick.provider"})
@EnableConfigurationProperties({RealtimeRouterProperties.class, com.surprising.candlestick.provider.config.CandlestickProperties.class})
public class RealtimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
