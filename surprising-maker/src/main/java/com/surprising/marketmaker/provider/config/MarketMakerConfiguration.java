package com.surprising.marketmaker.provider.config;

import com.surprising.aeron.client.SurprisingAeronClient;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class MarketMakerConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "surprising.market-maker")
    MarketMakerProperties marketMakerProperties(MarketMakerInfrastructureProperties infrastructure) {
        var properties = new MarketMakerProperties();
        properties.setProductLine(infrastructure.getProductLine());
        properties.getEngine().setNodeId(infrastructure.getNodeId());
        return properties;
    }

    @Bean(name = "makerMediaDriver", destroyMethod = "close")
    MediaDriver makerMediaDriver(Environment env) {
        boolean aeronEnabled = env.getProperty("surprising.market-maker.aeron.enabled", Boolean.class, true);
        boolean realtimeEnabled = env.getProperty("surprising.realtime.enabled", Boolean.class, true);
        if (!aeronEnabled && !realtimeEnabled) {
            return null;
        }
        String directory = env.getProperty("AERON_DIR",
                env.getProperty("surprising.realtime.directory", CommonContext.getAeronDirectoryName()));
        try {
            MediaDriver driver = SurprisingAeronClient.newMediaDriver(directory);
            log.info("做市商共享 Aeron MediaDriver 启动成功: dir={}", driver.aeronDirectoryName());
            return driver;
        } catch (Exception ex) {
            log.warn("做市商 Aeron MediaDriver 初始化跳过: dir={} error={}", directory, ex.getMessage());
            return null;
        }
    }
}
