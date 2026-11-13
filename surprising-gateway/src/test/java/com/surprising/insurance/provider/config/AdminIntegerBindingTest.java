package com.surprising.insurance.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class AdminIntegerBindingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740993", "9223372036854775807", "-9223372036854775808"})
    void insuranceAdjustmentKeepsTheConfirmedSignedUnitsExactly(String units) {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            var request = json.readValue(body("\"" + units + "\""), InsuranceFundAdjustmentRequest.class);
            assertThat(request.amountUnits()).isEqualTo(Long.parseLong(units));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1.5\"", "9223372036854775808", "\"9223372036854775808\"",
            "-9223372036854775809", "\"-9223372036854775809\""})
    void insuranceRejectsFractionalAndOverflowingUnits(String invalid) {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> json.readValue(body(invalid), InsuranceFundAdjustmentRequest.class))
                    .isInstanceOf(JacksonException.class);
        });
    }

    private String body(String amount) {
        return "{\"asset\":\"USDT\",\"amountUnits\":" + amount
                + ",\"referenceId\":\"admin-exact-integer\",\"reason\":\"precision\"}";
    }
}
