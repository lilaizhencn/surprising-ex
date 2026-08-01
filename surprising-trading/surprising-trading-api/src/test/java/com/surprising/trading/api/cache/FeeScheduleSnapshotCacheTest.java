package com.surprising.trading.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeeScheduleSnapshotCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void riskOverrideWinsOverSymbolVipAndUserSymbolWinsOverUserGlobal() {
        FeeScheduleSnapshotCache cache = new FeeScheduleSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(
                schedule(1L, "BTC-USDT", FeeScheduleSourceType.VIP, 100L, NOW.minusSeconds(10)),
                schedule(2L, null, FeeScheduleSourceType.RISK_OVERRIDE, 900L, NOW.minusSeconds(20)),
                schedule(3L, null, FeeScheduleSourceType.USER_OVERRIDE, 300L, NOW.minusSeconds(30)),
                schedule(4L, "BTC-USDT", FeeScheduleSourceType.USER_OVERRIDE, 200L, NOW.minusSeconds(40))));

        assertThat(cache.effective(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", NOW))
                .get()
                .extracting(FeeScheduleResponse::feeScheduleId)
                .isEqualTo(2L);

        FeeScheduleSnapshotCache userCache = new FeeScheduleSnapshotCache();
        userCache.replace(ProductLine.LINEAR_PERPETUAL, List.of(
                schedule(5L, null, FeeScheduleSourceType.USER_OVERRIDE, 300L, NOW.minusSeconds(30)),
                schedule(6L, "BTC-USDT", FeeScheduleSourceType.USER_OVERRIDE, 200L, NOW.minusSeconds(40))));
        assertThat(userCache.effective(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", NOW))
                .get()
                .extracting(FeeScheduleResponse::feeScheduleId)
                .isEqualTo(6L);
    }

    @Test
    void expiredOrDisabledSchedulesAreNotEffective() {
        FeeScheduleSnapshotCache cache = new FeeScheduleSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(
                schedule(7L, "BTC-USDT", FeeScheduleSourceType.USER_OVERRIDE, 200L,
                        NOW.minusSeconds(20), NOW.minusSeconds(1), FeeScheduleStatus.ACTIVE),
                schedule(8L, "BTC-USDT", FeeScheduleSourceType.VIP, 100L,
                        NOW.minusSeconds(20), null, FeeScheduleStatus.DISABLED)));

        assertThat(cache.effective(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", NOW)).isEmpty();
    }

    private FeeScheduleResponse schedule(long id,
                                         String symbol,
                                         FeeScheduleSourceType source,
                                         long maker,
                                         Instant effective) {
        return schedule(id, symbol, source, maker, effective, null, FeeScheduleStatus.ACTIVE);
    }

    private FeeScheduleResponse schedule(long id,
                                         String symbol,
                                         FeeScheduleSourceType source,
                                         long maker,
                                         Instant effective,
                                         Instant expire,
                                         FeeScheduleStatus status) {
        return new FeeScheduleResponse(id, ProductLine.LINEAR_PERPETUAL, 1001L, symbol, maker, maker + 100,
                source, null, "test", status, effective, expire, effective, effective);
    }
}
