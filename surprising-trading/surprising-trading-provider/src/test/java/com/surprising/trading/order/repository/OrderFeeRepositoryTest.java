package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderFeeRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void loadsAllSchedulesForStartupSnapshotOnly() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderFeeRepository repository = new OrderFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM trading_fee_schedules"), any(RowMapper.class),
                eq(ProductLine.LINEAR_PERPETUAL.name()))).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row(7L, ProductLine.LINEAR_PERPETUAL, 200L, "BTC-USDT",
                            200L, 500L, "VIP"), 0));
                });

        assertThat(repository.loadSnapshotSchedules(ProductLine.LINEAR_PERPETUAL)).hasSize(1);
    }

    @Test
    void validatesVipScheduleWithMakerRebate() {
        FeeScheduleUpsertRequest request = new FeeScheduleUpsertRequest(null, ProductLine.LINEAR_PERPETUAL,
                1001L, "BTC-USDT", -50L, 350L, FeeScheduleSourceType.VIP, "VIP3", "vip fee tier",
                FeeScheduleStatus.ACTIVE, Instant.parse("2026-07-01T00:00:00Z"), null);

        OrderFeeRepository.validateSchedule(request);
    }

    @Test
    void rejectsScheduleWhenMakerRateIsWorseThanTakerRate() {
        FeeScheduleUpsertRequest request = new FeeScheduleUpsertRequest(null, ProductLine.LINEAR_PERPETUAL,
                1001L, "BTC-USDT", 600L, 500L, FeeScheduleSourceType.USER_OVERRIDE, null, "bad fee",
                FeeScheduleStatus.ACTIVE, Instant.parse("2026-07-01T00:00:00Z"), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OrderFeeRepository.validateSchedule(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("makerFeeRatePpm cannot exceed takerFeeRatePpm");
    }

    private ResultSet row(long id,
                          ProductLine productLine,
                          long userId,
                          String symbol,
                          long makerFeeRatePpm,
                          long takerFeeRatePpm,
                          String source) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("fee_schedule_id")).thenReturn(id);
        when(rs.getString("product_line")).thenReturn(productLine.name());
        when(rs.getLong("user_id")).thenReturn(userId);
        when(rs.getString("symbol")).thenReturn(symbol);
        when(rs.getLong("maker_fee_rate_ppm")).thenReturn(makerFeeRatePpm);
        when(rs.getLong("taker_fee_rate_ppm")).thenReturn(takerFeeRatePpm);
        when(rs.getString("source_type")).thenReturn(source);
        when(rs.getString("tier_code")).thenReturn("VIP1");
        when(rs.getString("reason")).thenReturn("test");
        when(rs.getString("status")).thenReturn(FeeScheduleStatus.ACTIVE.name());
        Timestamp effective = Timestamp.from(Instant.parse("2026-07-01T00:00:00Z"));
        when(rs.getTimestamp("effective_time")).thenReturn(effective);
        when(rs.getTimestamp("expire_time")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(effective);
        when(rs.getTimestamp("updated_at")).thenReturn(effective);
        return rs;
    }
}
