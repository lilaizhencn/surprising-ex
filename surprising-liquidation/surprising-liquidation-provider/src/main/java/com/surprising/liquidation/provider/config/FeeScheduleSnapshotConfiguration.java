package com.surprising.liquidation.provider.config;

import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 强平模块只维护一个费率 JVM 快照实例。 */
@Configuration
public class FeeScheduleSnapshotConfiguration {

    @Bean
    public FeeScheduleSnapshotCache feeScheduleSnapshotCache() {
        return new FeeScheduleSnapshotCache();
    }
}
