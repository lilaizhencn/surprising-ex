package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Only deployment connections bind from the environment; business settings load from PostgreSQL. */
@Configuration(proxyBeanMethods = false)
public class LifecycleInfrastructureConfiguration {
    @Bean
    public LiquidationProperties liquidationProperties(Environment environment, RiskProperties risk) {
        var properties = new LiquidationProperties();
        properties.setProductLine(risk.getProductLine());
        Binder.get(environment).bind("surprising.liquidation.aeron", Bindable.ofInstance(properties.getAeron()));
        return properties;
    }

    @Bean
    public InsuranceProperties insuranceProperties(Environment environment, RiskProperties risk) {
        var properties = new InsuranceProperties();
        properties.getKafka().setProductLine(risk.getProductLine());
        Binder.get(environment).bind("surprising.insurance.kafka", Bindable.ofInstance(properties.getKafka()));
        if (properties.getKafka().getProductLine() != risk.getProductLine())
            throw new IllegalStateException("insurance and lifecycle must use the same product line");
        return properties;
    }

    @Bean
    public AdlProperties adlProperties(RiskProperties risk) {
        var properties = new AdlProperties();
        properties.getKafka().setProductLine(risk.getProductLine());
        return properties;
    }
}
