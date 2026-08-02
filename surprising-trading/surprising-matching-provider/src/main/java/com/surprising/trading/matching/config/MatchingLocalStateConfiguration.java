package com.surprising.trading.matching.config;

import com.surprising.trading.matching.store.MatchingLocalStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 撮合命令、成交和订单簿恢复使用的本地同步状态库。 */
@Configuration
public class MatchingLocalStateConfiguration {

    @Bean(destroyMethod = "close")
    public MatchingLocalStateStore matchingLocalStateStore(MatchingProperties properties,
                                                           ObjectMapper objectMapper) {
        return new MatchingLocalStateStore(
                properties.getWal().productLineDirectory(properties.getKafka().getProductLine()), objectMapper);
    }
}
