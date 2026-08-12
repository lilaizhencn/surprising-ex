package com.surprising.liquidation.provider.config;

import com.surprising.account.api.cache.PositionSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 强平服务的永续持仓 JVM 快照配置；快照只接受当前产品线事件。 */
@Configuration
public class LiquidationPositionSnapshotConfiguration {

    @Bean
    public PositionSnapshotCache liquidationPositionSnapshot(LiquidationProperties properties) {
        return new PositionSnapshotCache(properties.getKafka().getProductLine());
    }
}
