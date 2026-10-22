package com.surprising.aeron.bootstrap;

import com.surprising.aeron.config.CoreTransportConfiguration;
import io.aeron.driver.ThreadingMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网络参数在创建任何 Aeron 线程之前绑定、校验，运行时不再读取系统属性。 */
class SurprisingCoreApplicationNetworkingTest {

    private static CoreTransportConfiguration bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("surprising.aeron", Bindable.of(CoreTransportConfiguration.class));
    }

    @Test
    void retainsNetworkDefaultsAndAeronServiceIdleStrategy() {
        var configuration = bind(Map.of());
        assertThat(configuration.maxConcurrentSessions()).isEqualTo(64);
        assertThat(configuration.clusterIngressChannel()).isEqualTo("aeron:udp?term-length=16m");
        assertThat(configuration.archiveLocalControlChannel()).isEqualTo("aeron:ipc?term-length=1m");
        assertThat(configuration.service().idleStrategy()).isNull();
        assertThat(configuration.core().threadingMode()).isNotNull();
    }

    @Test
    void bindsTypedDriverAndServiceSettingsWithNetworkChannels() {
        var configuration = bind(Map.of(
                "surprising.aeron.max-concurrent-sessions", "128",
                "surprising.aeron.core.threading-mode", "dedicated",
                "surprising.aeron.service.idle-strategy", "yielding",
                "surprising.aeron.cluster-ingress-channel", " aeron:udp?term-length=8m ",
                "surprising.aeron.archive-local-control-channel", "aeron:ipc?term-length=2m"));
        assertThat(configuration.maxConcurrentSessions()).isEqualTo(128);
        assertThat(configuration.core().threadingMode()).isEqualTo(ThreadingMode.DEDICATED);
        assertThat(configuration.service().idleStrategy())
                .isEqualTo(CoreTransportConfiguration.ServiceIdleStrategy.YIELDING);
        assertThat(configuration.clusterIngressChannel()).isEqualTo("aeron:udp?term-length=8m");
        assertThat(configuration.archiveLocalControlChannel()).isEqualTo("aeron:ipc?term-length=2m");
    }

    @Test
    void emptyEnvironmentPlaceholdersPreserveCpuAndAeronDefaults() {
        var configuration = bind(Map.of(
                "surprising.aeron.core.threading-mode", "",
                "surprising.aeron.service.idle-strategy", ""));
        assertThat(configuration.core().threadingMode()).isEqualTo(
                Runtime.getRuntime().availableProcessors() >= 12
                        ? ThreadingMode.DEDICATED : ThreadingMode.SHARED_NETWORK);
        assertThat(configuration.service().idleStrategy()).isNull();
    }

    @Test
    void acceptsSessionLimitBoundaries() {
        for (int limit : new int[] {1, 1_024}) {
            assertThat(bind(Map.of("surprising.aeron.max-concurrent-sessions", limit))
                    .maxConcurrentSessions()).isEqualTo(limit);
        }
    }

    @Test
    void rejectsMalformedSettingsBeforeNetworkStartup() {
        for (var invalid : Map.of(
                "core.threading-mode", "invalid",
                "service.idle-strategy", "invalid",
                "cluster-ingress-channel", "http://localhost:1234",
                "archive-local-control-channel", "aeron:ipc?broken").entrySet()) {
            assertThatThrownBy(() -> bind(Map.of("surprising.aeron." + invalid.getKey(), invalid.getValue())))
                    .as("invalid setting %s", invalid.getKey())
                    .isInstanceOf(RuntimeException.class);
        }
        for (int limit : new int[] {0, 1_025}) {
            assertThatThrownBy(() -> bind(Map.of("surprising.aeron.max-concurrent-sessions", limit)))
                    .hasStackTraceContaining("must be in [1,1024]");
        }
    }

    @Test
    void alreadyBoundConfigurationDoesNotReadChangedSystemProperties() {
        var configuration = bind(Map.of("surprising.aeron.core.threading-mode", "SHARED"));
        String key = "surprising.aeron.core.threading-mode";
        String before = System.setProperty(key, "INVALID");
        try {
            assertThat(configuration.core().threadingMode()).isEqualTo(ThreadingMode.SHARED);
        } finally {
            if (before == null) System.clearProperty(key); else System.setProperty(key, before);
        }
    }
}
