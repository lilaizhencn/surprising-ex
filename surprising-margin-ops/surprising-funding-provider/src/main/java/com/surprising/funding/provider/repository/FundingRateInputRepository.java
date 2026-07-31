package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Duration;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 读取资金费率计算所需的当前合约参数。
 *
 * <p>不可拆原因：费率计算必须在同一数据库快照中确认 instrument_current_versions
 * 指向的 instruments 版本，并与同一批内存标记价按 symbol、version 精确匹配。拆成多次查询会让版本切换窗口
 * 产生旧价格配新参数或新价格配旧参数的资金风险。该查询只服务在线资金费计算，不承担报表或运营查询。</p>
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
                                      InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.markPriceCache = markPriceCache;
        this.snapshotCache = snapshotCache;
    }

    public List<FundingRateInput> find(Duration maxMarkAge) {
        MarkPriceValues markPrices = freshMarkPrices(maxMarkAge);
        if (markPrices.isEmpty()) {
            return List.of();
        }
        if (snapshotCache != null && snapshotCache.initialized(properties.getKafka().getProductLine())) {
            return fromSnapshot(markPriceCache.freshSnapshots(maxMarkAge));
        }
        List<Object> args = new ArrayList<>(markPrices.args());
        String productCondition = fundingInstrumentCondition(args, "i");
        return jdbcTemplate.query("""
                WITH %s
                SELECT i.symbol,
                       CAST(round(((pm.mark_price - pm.index_price) / pm.index_price) * 1000000) AS BIGINT)
                           AS premium_rate_ppm,
                       i.interest_rate_ppm,
                       i.funding_rate_floor_ppm,
                       i.funding_rate_cap_ppm,
                       i.funding_interval_hours,
                       pm.event_time
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN mark_prices pm
                    ON pm.symbol = i.symbol
                   AND pm.instrument_version = i.version
                 WHERE i.status = 'TRADING'
                   AND %s
                   AND i.funding_interval_hours > 0
                   AND pm.index_price > 0
                """.formatted(markPrices.cte(), productCondition), (rs, rowNum) -> new FundingRateInput(
                rs.getString("symbol"),
                0L,
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getLong("funding_rate_floor_ppm"),
                rs.getLong("funding_rate_cap_ppm"),
                rs.getInt("funding_interval_hours"),
                rs.getTimestamp("event_time").toInstant()), args.toArray());
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

    private MarkPriceValues freshMarkPrices(Duration maxAge) {
        List<MarkPriceEvent> snapshots = markPriceCache.freshSnapshots(maxAge);
        if (snapshots.isEmpty()) {
            return MarkPriceValues.empty();
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(snapshots.size() * 5);
        for (MarkPriceEvent snapshot : snapshots) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::TEXT, ?::BIGINT, ?::NUMERIC, ?::NUMERIC, ?::TIMESTAMPTZ)");
            args.add(snapshot.symbol());
            args.add(snapshot.instrumentVersion());
            args.add(snapshot.markPrice());
            args.add(snapshot.indexPrice());
            args.add(Timestamp.from(snapshot.eventTime()));
        }
        return new MarkPriceValues("mark_prices(symbol, instrument_version, mark_price, index_price, event_time) "
                + "AS (VALUES " + values + ")", List.copyOf(args));
    }

    private String fundingInstrumentCondition(List<Object> args, String alias) {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (properties.getKafka().isProductTopicsEnabled()) {
            if (!productLine.isFundingProduct()) {
                return "1 = 0";
            }
            args.add(productLine.contractTypeCode());
            return alias + ".contract_type = ?";
        }
        return alias + ".instrument_type = 'PERPETUAL'";
    }

    private record MarkPriceValues(String cte, List<Object> args) {

        private static MarkPriceValues empty() {
            return new MarkPriceValues("", List.of());
        }

        private boolean isEmpty() {
            return args.isEmpty();
        }
    }
}
