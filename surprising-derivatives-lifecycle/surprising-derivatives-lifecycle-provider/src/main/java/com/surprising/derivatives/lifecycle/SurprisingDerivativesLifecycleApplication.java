package com.surprising.derivatives.lifecycle;

import com.surprising.risk.provider.config.RiskProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration("derivativesLifecycleConfiguration")
@EnableConfigurationProperties(RiskProperties.class)
public class SurprisingDerivativesLifecycleApplication {
}
