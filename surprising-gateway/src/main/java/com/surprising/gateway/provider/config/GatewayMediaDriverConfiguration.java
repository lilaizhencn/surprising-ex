package com.surprising.gateway.provider.config;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Gateway owns the app-side driver; other local apps connect to the same mapped directory. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public class GatewayMediaDriverConfiguration {
    @Bean(destroyMethod = "close")
    public MediaDriver appMediaDriver(Environment environment) {
        String directory = environment.getRequiredProperty("surprising.realtime.directory");
        if (directory.isBlank()) throw new IllegalArgumentException("Realtime driver directory is required");
        return MediaDriver.launch(new MediaDriver.Context()
                .aeronDirectoryName(directory)
                .threadingMode(ThreadingMode.SHARED)
                // Never delete a live driver's files. Aeron checks the heartbeat before reclaiming stale files.
                .dirDeleteOnStart(false)
                // External clients must detect shutdown before reconnecting to the next driver instance.
                .dirDeleteOnShutdown(false));
    }
}
