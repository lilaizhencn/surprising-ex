package com.surprising.insurance.provider;

import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka
@EnableScheduling
@EnableFeignClients(clients = InstrumentRpcApi.class)
@EnableConfigurationProperties(InsuranceProperties.class)
public class SurprisingInsuranceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingInsuranceApplication.class, args);
    }
}
