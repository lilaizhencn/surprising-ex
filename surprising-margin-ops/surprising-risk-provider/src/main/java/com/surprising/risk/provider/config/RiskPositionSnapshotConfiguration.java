package com.surprising.risk.provider.config;

import com.surprising.account.api.cache.PositionSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 风控服务的永续持仓 JVM 快照；事件成功进入风险计算后再推进快照。 */
@Configuration
public class RiskPositionSnapshotConfiguration {

    @Bean
    public PositionSnapshotCache riskPositionSnapshot(RiskProperties properties) {
        return new PositionSnapshotCache(properties.getKafka().getProductLine());
    }
}
