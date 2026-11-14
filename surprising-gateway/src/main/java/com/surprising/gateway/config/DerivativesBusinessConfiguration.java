package com.surprising.gateway.config;

import com.surprising.risk.provider.config.RiskProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** 仅注册进衍生品产品线的生命周期与风控容器；SPOT 现货产品线不加载衍生品组件。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${surprising.risk.product-line:LINEAR_PERPETUAL}' != 'SPOT'")
@ComponentScan(basePackages = {
        "com.surprising.derivatives.lifecycle",
        "com.surprising.risk.provider", "com.surprising.liquidation.provider",
        "com.surprising.insurance.provider", "com.surprising.adl.provider"
}, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
                com.surprising.derivatives.lifecycle.SurprisingDerivativesLifecycleApplication.class,
                com.surprising.derivatives.lifecycle.config.LifecycleSchedulingConfiguration.class
        }))
@EnableConfigurationProperties(RiskProperties.class)
public class DerivativesBusinessConfiguration {
}
