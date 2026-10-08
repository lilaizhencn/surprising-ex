package com.surprising.marketmaker.provider;

import lombok.extern.slf4j.Slf4j;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import jakarta.annotation.PostConstruct;
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
        OrderRpcApi.class,
        com.surprising.marketmaker.provider.client.MakerTradingFeeClient.class,
        com.surprising.marketmaker.provider.client.MakerQuoteInputsClient.class
})
@EnableConfigurationProperties(com.surprising.marketmaker.provider.config.MarketMakerInfrastructureProperties.class)
@Slf4j
public class SurprisingMarketMakerApplication {


    private final MarketMakerProperties properties;

    public SurprisingMarketMakerApplication(MarketMakerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logEffectiveMarketMatrixConfiguration() {
        properties.validateBusinessSettings();
        log.info("Single-instance maker: YAML startup configuration, in-memory administrator changes; productLine={}",
                properties.getProductLine());
    }

    public static void main(String[] args) {
        SpringApplication.run(SurprisingMarketMakerApplication.class, args);
    }
}
