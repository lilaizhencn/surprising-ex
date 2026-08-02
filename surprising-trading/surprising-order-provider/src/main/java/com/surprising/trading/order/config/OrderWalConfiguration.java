package com.surprising.trading.order.config;

import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.trading.order.service.OrderIdSequenceStore;
import com.surprising.trading.order.service.CancelAllAfterLocalStateStore;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 订单用户分区事实流的本地 WAL、状态库和单写入队列。 */
@Configuration
public class OrderWalConfiguration {

    @Bean(destroyMethod = "close")
    public UserPartitionWal orderUserPartitionWal(TradingOrderProperties properties) {
        Path directory = Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name());
        return new UserPartitionWal(directory);
    }

    @Bean(destroyMethod = "close")
    public UserPartitionStateStore orderUserPartitionStateStore(TradingOrderProperties properties) {
        Path directory = Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "state");
        return new UserPartitionStateStore(directory);
    }

    @Bean
    public UserPartitionCommandLane orderUserPartitionCommandLane() {
        return new UserPartitionCommandLane();
    }

    @Bean(destroyMethod = "close")
    public OrderIdSequenceStore orderIdSequenceStore(TradingOrderProperties properties) {
        return new OrderIdSequenceStore(
                Path.of(properties.getWal().getDirectory(),
                        properties.getKafka().getProductLine().name(), "sequence"),
                properties.getWal().getNodeId());
    }

    @Bean(destroyMethod = "close")
    public CancelAllAfterLocalStateStore cancelAllAfterLocalStateStore(TradingOrderProperties properties,
                                                                       ObjectMapper objectMapper) {
        return new CancelAllAfterLocalStateStore(
                Path.of(properties.getWal().getDirectory(),
                        properties.getKafka().getProductLine().name(), "cancel-all-after"), objectMapper);
    }
}
