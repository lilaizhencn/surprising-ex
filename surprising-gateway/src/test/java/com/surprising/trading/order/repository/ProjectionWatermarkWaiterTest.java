package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectionWatermarkWaiterTest {

    @Test
    void exactWatermarkSucceedsWithoutWaiting() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(7L);
        ProjectionWatermarkWaiter waiter = new ProjectionWatermarkWaiter(jdbcTemplate,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), millis -> { throw new AssertionError("must not wait"); }, 4);

        ProjectionReadResult result = waiter.await(ProductLine.SPOT, 7L, 0);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.OK);
        assertThat(result.observedExportSequence()).isEqualTo(7L);
        assertThat(result.requiredExportSequence()).isEqualTo(7L);
        verify(jdbcTemplate).queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name()));
    }

    @Test
    void lagExpiresAtFakeClockDeadlineWithObservedAndRequiredValues() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AtomicLong now = new AtomicLong();
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(3L);
        ProjectionWatermarkWaiter waiter = new ProjectionWatermarkWaiter(jdbcTemplate,
                new MutableClock(now), now::addAndGet, 8);

        ProjectionReadResult result = waiter.await(ProductLine.SPOT, 9L, 5);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.PROJECTION_LAG);
        assertThat(result.observedExportSequence()).isEqualTo(3L);
        assertThat(result.requiredExportSequence()).isEqualTo(9L);
        assertThat(now).hasValue(5L);
    }

    @Test
    void waitBoundIsLimitedToTwoSeconds() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        ProjectionWatermarkWaiter waiter = new ProjectionWatermarkWaiter(jdbcTemplate);

        assertThatThrownBy(() -> waiter.await(ProductLine.SPOT, 1L, 2001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[0, 2000]");
    }

    @Test void usesBoundedBackoffAgainstCommittedPgWatermark() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq("SPOT"))).thenReturn(3L);
        AtomicLong now = new AtomicLong();
        var waits = new java.util.ArrayList<Long>();
        var waiter = new ProjectionWatermarkWaiter(jdbc, new MutableClock(now), ms -> { waits.add(ms); now.addAndGet(ms); });
        assertThat(waiter.await(ProductLine.SPOT, 9, 2000).status()).isEqualTo(ProjectionReadResult.Status.PROJECTION_LAG);
        assertThat(now).hasValue(2000);
        assertThat(waits.subList(0, 4)).containsExactly(10L, 20L, 40L, 80L);
        assertThat(waits).allMatch(ms -> ms > 0 && ms <= 100);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(24)).queryForObject(any(String.class), eq(Long.class), eq("SPOT"));
    }

    @Test void backoffStillReturnsAsSoonAsCommittedWatermarkCatchesUp() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq("SPOT"))).thenReturn(3L, 9L);
        AtomicLong now = new AtomicLong();
        var waiter = new ProjectionWatermarkWaiter(jdbc, new MutableClock(now), now::addAndGet);
        assertThat(waiter.await(ProductLine.SPOT, 9, 2000).status()).isEqualTo(ProjectionReadResult.Status.OK);
        assertThat(now).hasValue(10);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(AtomicLong millis) {
            this.millis = millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}
