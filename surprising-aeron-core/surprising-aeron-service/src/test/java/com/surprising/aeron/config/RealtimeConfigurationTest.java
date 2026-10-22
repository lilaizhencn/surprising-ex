package com.surprising.aeron.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class RealtimeConfigurationTest {
    private RealtimeConfiguration bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("surprising.realtime", Bindable.of(RealtimeConfiguration.class));
    }
    @Test void disabledByDefaultAndUsesPlatformDirectory() {
        var configuration = bind(Map.of());
        assertThat(configuration.channel()).isEmpty();
        assertThat(configuration.directory()).isNotBlank();
    }
    @Test void validatesChannelsAndStreamsBeforeCreatingResources() {
        assertThatThrownBy(() -> bind(Map.of("surprising.realtime.stream", "0"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bind(Map.of("surprising.realtime.channel", "invalid"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bind(Map.of("surprising.realtime.control-channel", "aeron:ipc"))).isInstanceOf(RuntimeException.class);
        var configuration = bind(Map.of("surprising.realtime.channel", "aeron:ipc",
                "surprising.realtime.control-channel", "aeron:ipc", "surprising.realtime.stream", "2201"));
        assertThat(configuration.stream()).isEqualTo(2201);
    }
}
