package com.surprising.trading.order.config;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 订单服务的永续账户状态 JVM 快照；只有追赶到 Kafka 高水位后才允许读取。 */
@Configuration
@ConditionalOnExpression("'${surprising.trading.order.kafka.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL'")
public class OrderAccountStateSnapshotConfiguration {

    @Bean
    public PerpetualAccountStateSnapshotCache orderAccountStateSnapshot() {
        return new PerpetualAccountStateSnapshotCache();
    }
}
