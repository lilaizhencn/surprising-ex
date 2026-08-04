package com.surprising.account.provider.config;

import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.eventstore.UserPartitionResultStore;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 账户用户分区事实流的本地 WAL 和单写入队列配置。 */
@Configuration
public class AccountWalConfiguration {

    @Bean(destroyMethod = "close")
    public UserPartitionWal accountUserPartitionWal(AccountProperties properties,
                                                    UserPartitionCommandLane lane) {
        Path directory = Path.of(properties.getWal().getDirectory(), properties.getKafka().getProductLine().name());
        return new UserPartitionWal(directory, lane);
    }

    @Bean(destroyMethod = "close")
    public UserPartitionStateStore accountUserPartitionStateStore(AccountProperties properties,
                                                                  UserPartitionCommandLane lane) {
        Path directory = Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "state");
        return new UserPartitionStateStore(directory, lane);
    }

    @Bean(destroyMethod = "close")
    public UserPartitionResultStore accountUserPartitionResultStore(AccountProperties properties,
                                                                    UserPartitionCommandLane lane) {
        Path directory = Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "results");
        return new UserPartitionResultStore(directory, lane);
    }

    @Bean(destroyMethod = "close")
    public UserPartitionCommandLane accountUserPartitionCommandLane() {
        return new UserPartitionCommandLane();
    }
}
