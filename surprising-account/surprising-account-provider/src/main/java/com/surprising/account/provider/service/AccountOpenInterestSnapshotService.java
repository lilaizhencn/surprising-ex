package com.surprising.account.provider.service;

import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.api.model.OpenInterestSnapshotResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供未平仓量完整快照，其他模块只在启动或恢复时调用此入口。 */
@Service
public class AccountOpenInterestSnapshotService {

    private final AccountProperties properties;
    private final OpenInterestShardRepository repository;

    public AccountOpenInterestSnapshotService(AccountProperties properties,
                                              OpenInterestShardRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OpenInterestSnapshotResponse snapshot(ProductLine productLine) {
        ProductLineConfiguration.requireSame(properties.getKafka().getProductLine(), productLine, "account open interest");
        var rows = repository.snapshots(productLine);
        var shards = rows.stream()
                .map(row -> new OpenInterestShardSnapshot(row.productLine(), row.symbol(), row.shardId(),
                        row.longQuantitySteps(), row.shortQuantitySteps(), row.revision(), row.updatedAt()))
                .toList();
        long revision = rows.stream().mapToLong(OpenInterestShardRepository.OpenInterestShardState::revision)
                .max().orElse(0L);
        return new OpenInterestSnapshotResponse(productLine, revision, Instant.now(), shards);
    }
}
