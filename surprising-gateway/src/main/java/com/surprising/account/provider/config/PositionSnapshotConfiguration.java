package com.surprising.account.provider.config;

import com.surprising.account.api.cache.PositionSnapshotCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 账户模块的本地持仓快照配置；快照严格绑定当前产品线。 */
@Configuration
public class PositionSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(PositionSnapshotCache.class)
    public PositionSnapshotCache accountPositionSnapshotCache(AccountProperties properties) {
        return new PositionSnapshotCache(properties.getKafka().getProductLine());
    }
}
