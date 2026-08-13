package com.surprising.trading.matching.config;

import com.surprising.trading.matching.store.MatchingLocalStateStore;
import com.surprising.eventstore.PartitionOwnerLane;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 撮合命令、成交和订单簿恢复使用的本地同步状态库。 */
@Configuration
public class MatchingLocalStateConfiguration {

    @Bean(destroyMethod = "close")
    public MatchingLocalStateStore matchingLocalStateStore(MatchingProperties properties,
                                                           ObjectMapper objectMapper,
                                                           PartitionOwnerLane<String> matchingSymbolOwnerLane) {
        return new MatchingLocalStateStore(
                properties.getWal().productLineDirectory(properties.getKafka().getProductLine()),
                objectMapper, matchingSymbolOwnerLane, properties.getEngine().getBookShards());
    }

    @Bean(destroyMethod = "close")
    public PartitionOwnerLane<String> matchingSymbolOwnerLane() {
        return new PartitionOwnerLane<>(
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 32)),
                "matching-symbol-owner");
    }
}
