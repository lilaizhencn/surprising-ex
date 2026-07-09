package com.surprising.marginops.provider;

import com.surprising.adl.provider.SurprisingAdlApplication;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.SurprisingFundingApplication;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.SurprisingInsuranceApplication;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.SurprisingLiquidationApplication;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.surprising.liquidation.provider",
                "com.surprising.funding.provider",
                "com.surprising.insurance.provider",
                "com.surprising.adl.provider"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        SurprisingLiquidationApplication.class,
                        SurprisingFundingApplication.class,
                        SurprisingInsuranceApplication.class,
                        SurprisingAdlApplication.class
                }
        )
)
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({
        LiquidationProperties.class,
        FundingProperties.class,
        InsuranceProperties.class,
        AdlProperties.class
})
public class SurprisingMarginOpsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingMarginOpsApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-margin-ops-provider"));
        application.run(args);
    }
}
