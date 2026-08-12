package com.surprising.funding.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 资金费服务使用的合约不可变 JVM 快照。 */
@Configuration("fundingInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache fundingInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }

    /** 资金费候选持仓只读取账户发布的完整用户 JVM 快照。 */
    @Bean
    public PerpetualAccountStateSnapshotCache fundingAccountStateSnapshotCache(FundingProperties properties) {
        return new PerpetualAccountStateSnapshotCache(properties.getKafka().getProductLine());
    }
}
