package com.surprising.marketmaker.provider;

import com.surprising.account.api.client.AccountRpcApi;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.trading.api.client.MarketDataRpcApi;
import com.surprising.trading.api.client.OrderRpcApi;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SurprisingMarketMakerApplication.class);

    private final MarketMakerProperties properties;

    public SurprisingMarketMakerApplication(MarketMakerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logEffectiveMarketMatrixConfiguration() {
        int accountCount = properties.getStrategies().isEmpty()
                ? 0
                : properties.getStrategies().getFirst().getAccountIds().size();
        log.info("Effective maker matrix configuration cycleDelayMs={} orderLevels={} maxOperationsPerCycle={} accountCount={} referenceMarketEnabled={} marketTakingEnabled={}",
                properties.getEngine().getCycleDelayMs(), properties.getQuoting().getOrderLevels(),
                properties.getQuoting().getMaxOrderOperationsPerCycle(), accountCount,
                properties.getReferenceMarket().isEnabled(), properties.getTrade().isEnabled());
    }

    public static void main(String[] args) {
        SpringApplication.run(SurprisingMarketMakerApplication.class, args);
    }
}
