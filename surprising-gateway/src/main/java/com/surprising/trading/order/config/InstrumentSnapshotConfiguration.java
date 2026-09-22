package com.surprising.trading.order.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单服务使用的合约快照基础组件。
 */
@Configuration("orderInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache orderInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
