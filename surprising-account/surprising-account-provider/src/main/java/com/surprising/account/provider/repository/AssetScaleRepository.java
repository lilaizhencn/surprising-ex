package com.surprising.account.provider.repository;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账户资产精度快照仓储。 */
@Repository
public class AssetScaleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public AssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AccountProperties(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssetScaleRepository(JdbcTemplate jdbcTemplate,
                                AccountProperties properties,
                                InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new AccountProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    public OptionalLong findScaleUnits(String asset) {
        if (snapshotCache == null) {
            return OptionalLong.empty();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("账户资产精度 JVM 快照尚未就绪");
        }
        return snapshotCache.scale(productLine, asset).map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }
}
