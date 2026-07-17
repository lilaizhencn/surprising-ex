package com.surprising.marketmaker.provider;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableFeignClients(clients = {
        AccountRpcApi.class,
        InstrumentRpcApi.class,
        MarketDataRpcApi.class,
        OrderRpcApi.class
})
@EnableConfigurationProperties(MarketMakerProperties.class)
public class SurprisingMarketMakerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingMarketMakerApplication.class, args);
    }
}
