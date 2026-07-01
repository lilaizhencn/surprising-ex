package com.surprising.price.mark.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class MarkPriceRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void savePersistsQuoteUnitsAtMarkPriceBoundary() {
        MarkPriceRepository repository = new MarkPriceRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO price_mark_ticks"), any(Object[].class)))
                .thenReturn(1);

        repository.save(event());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("mark_price_units")
                .contains("CAST(round(? * qs.scale_units) AS BIGINT)")
                .contains("JOIN instrument_current_versions c")
                .contains("JOIN account_asset_scales qs")
                .doesNotContain("/ i.price_tick_units");
    }

    @Test
    void saveFailsWhenInstrumentScaleIsMissingOrInsertConflicts() {
        MarkPriceRepository repository = new MarkPriceRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO price_mark_ticks"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.save(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mark price insert failed");
    }

    private static MarkPriceEvent event() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new MarkPriceEvent("BTC-USDT", new BigDecimal("59000.00"), new BigDecimal("59000.00"),
                new BigDecimal("59000.00"), new BigDecimal("59000.00"), new BigDecimal("59000.00"),
                new BigDecimal("58999.00"), new BigDecimal("59001.00"), BigDecimal.ZERO,
                now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 300L, new BigDecimal("58000.00"),
                new BigDecimal("60000.00"), 9901L, PriceStatus.HEALTHY, now);
    }
}
