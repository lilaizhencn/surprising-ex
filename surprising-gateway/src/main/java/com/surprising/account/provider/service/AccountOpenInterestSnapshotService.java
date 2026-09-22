package com.surprising.account.provider.service;

import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.api.model.OpenInterestSnapshotResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 提供未平仓量完整快照，其他模块只在启动或恢复时调用此入口。 */
@Service
public class AccountOpenInterestSnapshotService {

    private final AccountProperties properties;
    private final AccountAeronGateway aeron;

    public AccountOpenInterestSnapshotService(AccountProperties properties,
                                              AccountAeronGateway aeron) {
        this.properties = properties;
        this.aeron = aeron;
    }

    public OpenInterestSnapshotResponse snapshot(ProductLine productLine) {
        ProductLineConfiguration.requireSame(properties.getKafka().getProductLine(), productLine, "account open interest");
        var state = aeron.openInterest();
        Instant now = Instant.now();
        long revision = Math.max(1L, state.revision());
        var shards = state.values().stream()
                .map(row -> new OpenInterestShardSnapshot(productLine, row.symbol(), 0,
                        row.longQuantitySteps(), row.shortQuantitySteps(), revision, now))
                .toList();
        return new OpenInterestSnapshotResponse(productLine, state.revision(), now, shards);
    }
}
