package com.surprising.liquidation.provider.config;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** 强平服务的永续完整账户 JVM 快照；恢复完成前只作为影子投影，不替换最终安全校验。 */
@Configuration
public class LiquidationAccountStateSnapshotConfiguration {

    @Bean
    @ConditionalOnExpression("'${surprising.liquidation.kafka.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL'")
    public PerpetualAccountStateSnapshotCache liquidationAccountStateSnapshot() {
        return new PerpetualAccountStateSnapshotCache();
    }
}
