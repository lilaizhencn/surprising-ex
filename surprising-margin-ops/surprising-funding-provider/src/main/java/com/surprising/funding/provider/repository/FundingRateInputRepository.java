package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 读取资金费率计算所需的当前合约参数。
 *
 * <p>费率计算直接使用本地不可变合约快照，并与同一批内存标记价按 symbol、version 精确匹配，
 * 避免版本切换窗口产生旧价格配新参数或新价格配旧参数。该查询只服务在线资金费计算，不承担报表或运营查询。</p>
 */
@Repository
public class FundingRateInputRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;
    private final LatestMarkPriceCache markPriceCache;
    private final InstrumentSnapshotCache snapshotCache;

    public FundingRateInputRepository(JdbcTemplate jdbcTemplate,
                                      FundingProperties properties,
                                      LatestMarkPriceCache markPriceCache) {
        this(jdbcTemplate, properties, markPriceCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FundingRateInputRepository(JdbcTemplate jdbcTemplate,
                                      FundingProperties properties,
                                      LatestMarkPriceCache markPriceCache,
                                      @org.springframework.beans.factory.annotation.Qualifier("fundingInstrumentSnapshotCache")
                                      InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.markPriceCache = markPriceCache;
        this.snapshotCache = snapshotCache;
    }

    public List<FundingRateInput> find(Duration maxMarkAge) {
        List<MarkPriceEvent> markPrices = markPriceCache.freshSnapshots(maxMarkAge);
        if (markPrices.isEmpty()) {
            return List.of();
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("资金费率合约 JVM 快照尚未就绪");
        }
        return fromSnapshot(markPrices);
    }

    private List<FundingRateInput> fromSnapshot(List<MarkPriceEvent> markPrices) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!productLine.isFundingProduct()) {
            return List.of();
        }
        List<FundingRateInput> result = new ArrayList<>(markPrices.size());
        for (MarkPriceEvent mark : markPrices) {
            var instrument = snapshotCache.version(productLine, mark.symbol(), mark.instrumentVersion()).orElse(null);
            if (instrument == null || instrument.status() != InstrumentStatus.TRADING
                    || instrument.fundingIntervalHours() <= 0 || mark.markPrice() == null
                    || mark.indexPrice() == null
                    || mark.indexPrice().signum() <= 0) {
                continue;
            }
            long premium = mark.markPrice().subtract(mark.indexPrice())
                    .multiply(java.math.BigDecimal.valueOf(1_000_000L))
                    .divide(mark.indexPrice(), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            result.add(new FundingRateInput(mark.symbol(), mark.sequence(), premium,
                    instrument.interestRatePpm(), instrument.fundingRateFloorPpm(),
                    instrument.fundingRateCapPpm(), instrument.fundingIntervalHours(), mark.eventTime()));
        }
        return List.copyOf(result);
    }

}
