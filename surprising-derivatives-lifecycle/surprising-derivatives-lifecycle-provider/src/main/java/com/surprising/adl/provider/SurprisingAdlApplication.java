package com.surprising.adl.provider;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableFeignClients(clients = InstrumentRpcApi.class)
@EnableConfigurationProperties(AdlProperties.class)
public class SurprisingAdlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingAdlApplication.class, args);
    }
}
