package com.surprising.trading.matching.repository;

import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.service.MatchingProtectionIndex;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 撮合保护只读取 JVM 索引；订单表仅由启动恢复仓储读取。 */
@Repository
public class MatchingProtectionRepository {

    private final LatestMarkPriceCache markPriceCache;
    private final MatchingProtectionIndex protectionIndex;

    /** 保留旧构造签名，避免影响外部测试；JdbcTemplate 不再用于撮合前查询。 */
    public MatchingProtectionRepository(JdbcTemplate ignoredJdbcTemplate) {
        this(ignoredJdbcTemplate, new MatchingProperties(), null, null);
    }

    public MatchingProtectionRepository(JdbcTemplate ignoredJdbcTemplate, MatchingProperties properties) {
        this(ignoredJdbcTemplate, properties, null, null);
    }

    public MatchingProtectionRepository(JdbcTemplate ignoredJdbcTemplate,
                                        MatchingProperties properties,
                                        LatestMarkPriceCache markPriceCache) {
        this(ignoredJdbcTemplate, properties, markPriceCache, null);
    }

    @Autowired
    public MatchingProtectionRepository(JdbcTemplate ignoredJdbcTemplate,
                                        MatchingProperties ignoredProperties,
                                        LatestMarkPriceCache markPriceCache,
                                        MatchingProtectionIndex protectionIndex) {
        this.markPriceCache = markPriceCache;
        this.protectionIndex = protectionIndex;
    }

    public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
        if (markPriceCache == null) {
            return OptionalLong.empty();
        }
        var event = markPriceCache.fresh(symbol, maxAge)
                .filter(value -> value.instrumentVersion() == instrumentVersion)
                .filter(value -> value.markPriceTicks() > 0);
        return event.isPresent() ? OptionalLong.of(event.orElseThrow().markPriceTicks()) : OptionalLong.empty();
    }

    public boolean wouldSelfTrade(long userId, String symbol, long instrumentVersion, OrderSide side,
                                  long effectivePriceTicks) {
        return requireReady().wouldSelfTrade(userId, symbol, instrumentVersion, side, effectivePriceTicks);
    }

    public Set<Long> commandsThatWouldSelfTrade(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        return requireReady().commandsThatWouldSelfTrade(commands);
    }

    public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol, long instrumentVersion, long orderId) {
        return requireReady().hasOpenOrdersWithDifferentInstrumentVersion(symbol, instrumentVersion, orderId);
    }

    public Set<Long> commandsWithOpenOrdersAtDifferentInstrumentVersion(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        return requireReady().commandsWithOpenOrdersAtDifferentInstrumentVersion(commands);
    }

    private MatchingProtectionIndex requireReady() {
        if (protectionIndex == null || !protectionIndex.ready()) {
            // 索引恢复完成前拒绝撮合保护请求，绝不以实时数据库查询替代索引。
            throw new IllegalStateException("撮合保护索引尚未就绪");
        }
        return protectionIndex;
    }
}
