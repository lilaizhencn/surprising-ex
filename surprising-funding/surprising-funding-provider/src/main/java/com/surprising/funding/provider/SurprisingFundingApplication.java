package com.surprising.funding.provider;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableFeignClients(clients = InstrumentRpcApi.class)
@EnableConfigurationProperties(FundingProperties.class)
@ImportRuntimeHints(FundingRuntimeHints.class)
public class SurprisingFundingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingFundingApplication.class, args);
    }
}
