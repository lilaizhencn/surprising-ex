package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.marketmaker.provider.controller.MarketMakerBusinessSettingsController;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import org.junit.jupiter.api.Test;
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

    @Test
    void actualMakerStrategyAndSettingsDtosPreserveLongIdsAndAmounts() {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            var definition = json.readValue(strategy("\"9007199254740993\",\"9223372036854775807\""),
                    MarketMakerStrategyDefinition.class);
            assertThat(definition.accountIds()).containsExactly(9_007_199_254_740_993L, Long.MAX_VALUE);
            assertThat(definition.baseQuantitySteps()).isEqualTo(9_007_199_254_740_993L);
            var update = json.readValue("""
                    {"expectedVersion":"9007199254740993","reason":"precision",
                     "settings":{"trade":{"accountIds":["9007199254740993","9223372036854775807"]}}}
                    """, MarketMakerBusinessSettingsController.Update.class);
            assertThat(update.expectedVersion()).isEqualTo(9_007_199_254_740_993L);
            assertThat(update.settings().trade().getAccountIds())
                    .containsExactly(9_007_199_254_740_993L, Long.MAX_VALUE);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1.5\"", "9223372036854775808", "\"9223372036854775808\""})
    void malformedIdsCannotBeTruncatedOrOverflowThroughEitherActualDto(String invalid) {
        context.run(application -> {
            var json = application.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> json.readValue(strategy(invalid), MarketMakerStrategyDefinition.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> json.readValue("{\"accountIds\":[" + invalid + "]}", MarketMakerProperties.Trade.class))
                    .isInstanceOf(JacksonException.class);
        });
    }

    private String strategy(String accounts) {
        return """
                {"strategyId":"exact-ids","productLine":"LINEAR_PERPETUAL","enabled":false,
                 "accountIds":[%s],"instrumentIds":["604"],"baseQuantitySteps":"9007199254740993",
                 "marginMode":"CROSS","spreadTicks":"1","levelSpacingTicks":"1",
                 "maxInventorySteps":"0","maxInventorySkewPpm":0,"orderLevels":1,
                 "initialAnchorPriceTicks":"0","version":"0"}
                """.formatted(accounts);
    }
}
