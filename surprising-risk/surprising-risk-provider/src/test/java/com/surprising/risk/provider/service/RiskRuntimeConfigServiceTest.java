package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    void runtimeUpdateOnlyChangesScanControls() {
        var service = new RiskRuntimeConfigService(new RiskProperties());

        assertThat(service.update(null, 25L, 10)).containsEntry("marginPolicySource", "AERON_CORE_INSTRUMENT");
    }
}
