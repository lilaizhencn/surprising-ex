package com.surprising.marginops.provider;

import com.surprising.adl.provider.SurprisingAdlApplication;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.SurprisingFundingApplication;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.SurprisingInsuranceApplication;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.SurprisingLiquidationApplication;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.SurprisingRiskApplication;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.service.RiskLocalProjectionStore;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.surprising.risk.provider",
                "com.surprising.liquidation.provider",
                "com.surprising.funding.provider",
                "com.surprising.insurance.provider",
                "com.surprising.adl.provider",
                "com.surprising.price.consumer"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        SurprisingRiskApplication.class,
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
        RiskProperties.class,
        LiquidationProperties.class,
        FundingProperties.class,
        InsuranceProperties.class,
        AdlProperties.class
})
public class SurprisingMarginOpsApplication {

    /**
     * 组合部署时不会加载独立风险应用类，必须在统一入口显式注册风险本地投影存储。
     * 风险热路径只写本地 WAL，数据库仅由异步投影任务用于恢复和审计。
     */
    @Bean(destroyMethod = "close")
    RiskLocalProjectionStore riskLocalProjectionStore(RiskProperties properties, ObjectMapper objectMapper) {
        return new RiskLocalProjectionStore(Path.of(properties.getLocalState().getWalDirectory()), objectMapper);
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingMarginOpsApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-margin-ops-provider"));
        application.run(args);
    }
}
