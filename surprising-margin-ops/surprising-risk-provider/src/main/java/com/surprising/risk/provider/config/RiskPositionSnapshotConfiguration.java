package com.surprising.risk.provider.config;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 风控服务的永续持仓 JVM 快照；事件成功进入风险计算后再推进快照。 */
@Configuration
public class RiskPositionSnapshotConfiguration {

    @Bean
    public PositionSnapshotCache riskPositionSnapshot(RiskProperties properties) {
        return new PositionSnapshotCache(properties.getKafka().getProductLine());
    }

    /** 风险服务的永续完整账户 JVM 快照；恢复完成前只作为影子投影，不参与计算。 */
    @Bean
    public PerpetualAccountStateSnapshotCache riskAccountStateSnapshot(RiskProperties properties) {
        return new PerpetualAccountStateSnapshotCache(properties.getKafka().getProductLine());
    }
}
