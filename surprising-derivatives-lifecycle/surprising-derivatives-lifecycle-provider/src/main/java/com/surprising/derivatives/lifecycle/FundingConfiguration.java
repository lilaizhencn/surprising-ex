package com.surprising.derivatives.lifecycle;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.risk.provider.config.RiskProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Only perpetual products create funding endpoints, consumers and settlement tasks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${surprising.risk.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL' || "
        + "'${surprising.risk.product-line:LINEAR_PERPETUAL}' == 'INVERSE_PERPETUAL'")
@ComponentScan(basePackages = "com.surprising.funding.provider",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@EnableConfigurationProperties(FundingProperties.class)
public class FundingConfiguration {
    public FundingConfiguration(FundingProperties funding, RiskProperties risk) {
        if (funding.getKafka().getProductLine() != risk.getProductLine()) {
            throw new IllegalStateException("funding and lifecycle must use the same product line");
        }
    }

    @Bean
    public ThreadPoolTaskScheduler fundingScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("funding-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
