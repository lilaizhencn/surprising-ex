package com.surprising.aeron.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class CoreRuntimeConfigurationTest {
    private CoreRuntimeConfiguration bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("surprising.aeron", Bindable.of(CoreRuntimeConfiguration.class));
    }

    @Test void defaultsAreBoundWithoutAContainer() {
        var configuration = bind(Map.of());
        assertThat(configuration.maxPendingMatching()).isEqualTo(4096);
        assertThat(configuration.laneCompletionSpins()).isEqualTo(1024);
    }

    @Test void bindsCapacityTogether() {
        var configuration = bind(Map.of(
                "surprising.aeron.max-pending-matching", "64",
                "surprising.aeron.snapshot-timeout-seconds", "900"));
        assertThat(configuration.maxPendingMatching()).isEqualTo(64);
        assertThat(configuration.snapshotTimeoutSeconds()).isEqualTo(900);
    }

    @Test void rejectsInvalidValuesBeforeAnyThreadStarts() {
        for (var invalid : Map.of(
                "max-pending-matching", "0",
                "lane-completion-spins", "-1",
                "snapshot-timeout-seconds", "0",
                "owner-command-window", "65", "owner-input-batch-size", "257").entrySet()) {
            assertThatThrownBy(() -> bind(Map.of("surprising.aeron." + invalid.getKey(), invalid.getValue())))
                    .isInstanceOf(RuntimeException.class);
        }
    }
    @Test void explicitConfigurationSurvivesActivationAndSnapshotRecovery() {
        var configuration = bind(Map.of("surprising.aeron.max-pending-matching", "64"));
        String previous = System.setProperty("surprising.aeron.max-pending-matching", "INVALID_LOW_PRIORITY_VALUE");
        try (var runtime = new com.surprising.aeron.service.core.TradingCoreRuntime(
                com.surprising.product.api.ProductLine.SPOT, com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY, configuration)) {
            runtime.activate();
            assertThat(runtime.runtimeState().accountLanesStarted()).isTrue();
            byte[] snapshot = runtime.snapshot(1);
            try (var restored = com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.decode(snapshot,
                    com.surprising.product.api.ProductLine.SPOT, configuration,
                    com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
                assertThat(restored.configuration()).isSameAs(configuration);
                assertThat(restored.runtimeState().topology()).isEqualTo(runtime.runtimeState().topology());
            }
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.max-pending-matching");
            else System.setProperty("surprising.aeron.max-pending-matching", previous);
        }
    }

    @Test void nodeRecoveryStaysPassiveUntilExplicitActivation() {
        var configuration = bind(Map.of());
        try (var original = new com.surprising.aeron.service.core.TradingCoreRuntime(
                com.surprising.product.api.ProductLine.SPOT, com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY, configuration)) {
            byte[] snapshot = original.snapshot(7);
            try (var restored = com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.decodePassive(snapshot,
                    com.surprising.product.api.ProductLine.SPOT, configuration,
                    com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
                assertThat(restored.activated()).isFalse();
                assertThat(restored.runtimeState().accountLanesStarted()).isFalse();
                restored.activate(TradingCpuAffinity.disabled());
                assertThat(restored.configuration()).isSameAs(configuration);
                assertThat(restored.activated()).isTrue();
                assertThat(restored.runtimeState().accountLanesStarted()).isTrue();
            }
        }
    }

}
