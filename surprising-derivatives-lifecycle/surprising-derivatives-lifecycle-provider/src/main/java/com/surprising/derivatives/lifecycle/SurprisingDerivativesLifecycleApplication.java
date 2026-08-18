package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.SurprisingAdlApplication;
import com.surprising.insurance.provider.SurprisingInsuranceApplication;
import com.surprising.liquidation.provider.SurprisingLiquidationApplication;
import com.surprising.risk.provider.SurprisingRiskApplication;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.surprising",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                SurprisingRiskApplication.class,
                SurprisingLiquidationApplication.class,
                SurprisingInsuranceApplication.class,
                SurprisingAdlApplication.class
        }))
@EnableKafka
@EnableScheduling
@EnableFeignClients(clients = InstrumentRpcApi.class)
@EnableConfigurationProperties({RiskProperties.class, LiquidationProperties.class, InsuranceProperties.class, AdlProperties.class})
public class SurprisingDerivativesLifecycleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurprisingDerivativesLifecycleApplication.class, args);
    }
}
