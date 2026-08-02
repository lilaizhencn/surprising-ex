package com.surprising.liquidation.provider.config;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 强平服务的永续完整账户 JVM 快照；恢复完成前只作为影子投影，不替换最终安全校验。 */
@Configuration
public class LiquidationAccountStateSnapshotConfiguration {

    @Bean
    public PerpetualAccountStateSnapshotCache liquidationAccountStateSnapshot(LiquidationProperties properties) {
        return new PerpetualAccountStateSnapshotCache(properties.getKafka().getProductLine());
    }
}
