package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayLimitProperties.Group;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class GatewayLimitPropertiesTest {
    @Test void yamlOverridesRefillPeriodAndRetainsTheOtherDefaultOperationGroups() {
        var properties = new GatewayLimitProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "surprising.gateway.limits.user.order.rate", "7",
                "surprising.gateway.limits.user.order.burst", "14",
                "surprising.gateway.limits.user.order.period", "1m")))
                .bind("surprising.gateway.limits", Bindable.ofInstance(properties));
        assertThat(properties.getUser().get(Group.ORDER).period()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getUser().get(Group.ORDER).rate()).isEqualTo(7);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test void incompleteOrUnboundedPoliciesAreRejectedByConfigurationValidation() {
        var properties = new GatewayLimitProperties();
        properties.getUser().remove(Group.CANCEL);
        properties.setLoginAccount(new GatewayLimitProperties.Rule(1, 1000000, Duration.ofDays(1)));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).extracting(v -> v.getPropertyPath().toString())
                    .contains("complete", "loginAccount.bounded");
        }
    }

    @Test void localProfileLimitsBindAndValidateCompletely() throws Exception {
        var sources = new MutablePropertySources();
        for (var source : new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml"))) {
            sources.addLast(source);
        }
        var properties = new GatewayLimitProperties();
        new Binder(ConfigurationPropertySources.from(sources))
                .bind("surprising.gateway.limits", Bindable.ofInstance(properties));
        assertThat(properties.getIp().rate()).isEqualTo(5000);
        assertThat(properties.getUser().get(Group.ORDER).rate()).isEqualTo(500);
        assertThat(properties.getUser().get(Group.CANCEL_ALL).rate()).isEqualTo(50);
        assertThat(properties.getProduct().get(Group.ORDER).rate()).isEqualTo(500);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }
}
