package com.surprising.funding.provider.config;

import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.funding.provider.service.FundingLocalSequenceStore;
import com.surprising.funding.provider.service.FundingLocalSettlementStore;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 资金费账户命令的本地 WAL 和 Kafka 发布游标。 */
@Configuration
public class FundingWalConfiguration {

    @Bean(destroyMethod = "close")
    public UserPartitionWal fundingAccountCommandWal(FundingProperties properties) {
        return new UserPartitionWal(Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "account-commands"));
    }

    @Bean(destroyMethod = "close")
    public UserPartitionStateStore fundingAccountCommandPublishState(FundingProperties properties) {
        return new UserPartitionStateStore(Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "account-command-publish-state"));
    }

    @Bean(destroyMethod = "close")
    public FundingLocalSequenceStore fundingLocalSequenceStore(FundingProperties properties) {
        return new FundingLocalSequenceStore(Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "rate-sequences"));
    }

    @Bean(destroyMethod = "close")
    public FundingLocalSettlementStore fundingLocalSettlementStore(FundingProperties properties,
                                                                   tools.jackson.databind.ObjectMapper objectMapper) {
        return new FundingLocalSettlementStore(Path.of(properties.getWal().getDirectory(),
                properties.getKafka().getProductLine().name(), "settlements"), objectMapper);
    }
}
