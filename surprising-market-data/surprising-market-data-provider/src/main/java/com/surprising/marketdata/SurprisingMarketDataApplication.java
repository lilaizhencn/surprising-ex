package com.surprising.marketdata;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.trading.matching.config.MatchingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({CandlestickProperties.class, MatchingProperties.class})
public class SurprisingMarketDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingMarketDataApplication.class, args);
    }
}
