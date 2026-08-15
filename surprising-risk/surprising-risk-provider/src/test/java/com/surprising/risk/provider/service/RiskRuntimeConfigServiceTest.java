package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.risk.provider.config.RiskProperties;
import org.junit.jupiter.api.Test;

class RiskRuntimeConfigServiceTest {

    @Test
    void exposesCoreAsTheOnlyMarginPolicySource() {
        var service = new RiskRuntimeConfigService(new RiskProperties());

        assertThat(service.current()).containsEntry("marginPolicySource", "AERON_CORE_INSTRUMENT");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> calculation =
                (java.util.Map<String, Object>) service.current().get("calculation");
        assertThat(calculation)
                .doesNotContainKey("warningMarginRatioPpm")
                .doesNotContainKey("liquidationMarginRatioPpm");
    }

    @Test
    void rejectsLocalMarginThresholdUpdates() {
        var service = new RiskRuntimeConfigService(new RiskProperties());

        assertThatThrownBy(() -> service.update(null, null, 800_000L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned by versioned Aeron Core instrument policy");
    }
}
