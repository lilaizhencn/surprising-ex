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
public class FundingConfiguration {
    @Bean
    public FundingProperties fundingProperties(org.springframework.core.env.Environment environment, RiskProperties risk) {
        var funding = new FundingProperties();
        funding.getKafka().setProductLine(risk.getProductLine());
        org.springframework.boot.context.properties.bind.Binder.get(environment).bind("surprising.funding.kafka",
                org.springframework.boot.context.properties.bind.Bindable.ofInstance(funding.getKafka()));
        funding.getCoordination().setNodeId(environment.getProperty("FUNDING_NODE_ID"));
        if (funding.getKafka().getProductLine() != risk.getProductLine()) {
            throw new IllegalStateException("funding and lifecycle must use the same product line");
        }
        return funding;
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
