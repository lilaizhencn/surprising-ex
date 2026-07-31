package com.surprising.candlestick.provider;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableFeignClients(basePackages = "com.surprising")
@EnableConfigurationProperties(CandlestickProperties.class)
public class SurprisingCandlestickApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingCandlestickApplication.class, args);
    }
}
