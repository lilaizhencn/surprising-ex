package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InstrumentRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final InstrumentRepository repository = new InstrumentRepository(jdbcTemplate);

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void listPageReturnsCursorMetadataForCurrentVersions() {
        List<InstrumentResponse> rows = List.of(
                response("ETH-USDT", 2, Instant.parse("2026-01-03T00:00:00Z")),
                response("BTC-USDT", 3, Instant.parse("2026-01-02T00:00:00Z")),
                response("ADA-USDT", 1, Instant.parse("2026-01-01T00:00:00Z")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn((List) rows);

        InstrumentRepository.InstrumentPage page =
                repository.listPage(null, InstrumentStatus.TRADING, 2, null, "updatedAt.desc");

        assertThat(page.instruments()).extracting(InstrumentResponse::symbol)
                .containsExactly("ETH-USDT", "BTC-USDT");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.sort()).isEqualTo("updatedAt.desc");
        assertThat(page.limit()).isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("ORDER BY i.updated_at DESC, i.symbol DESC LIMIT ?");
        assertThat(args.getValue()).containsExactly("TRADING", 3);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void versionsPageReturnsCursorMetadataForHistory() {
        List<InstrumentResponse> rows = List.of(
                response("BTC-USDT", 3, Instant.parse("2026-01-03T00:00:00Z")),
                response("BTC-USDT", 2, Instant.parse("2026-01-02T00:00:00Z")));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn((List) rows);

        InstrumentRepository.InstrumentPage page =
                repository.versionsPage("BTC-USDT", 1, null, "version.desc");

        assertThat(page.instruments()).extracting(InstrumentResponse::version).containsExactly(3L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.sort()).isEqualTo("version.desc");
        assertThat(page.limit()).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("WHERE symbol = ?").contains("ORDER BY version DESC LIMIT ?");
        assertThat(args.getValue()).containsExactly("BTC-USDT", 2);
    }

    @Test
    void listPageRejectsUnsupportedSort() {
        assertThatThrownBy(() -> repository.listPage(null, null, 10, null, "status.desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported sort");
    }

    private InstrumentResponse response(String symbol, long version, Instant updatedAt) {
        return new InstrumentResponse(
                symbol,
                version,
                InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL,
                "BTC",
                "USDT",
                "USDT",
                1_000_000L,
                "USDT",
                100L,
                1L,
                1L,
                1_000_000L,
                1L,
                1_000_000_000L,
                1L,
                2,
                0,
                List.of("LIMIT", "MARKET"),
                List.of("GTC", "IOC"),
                true,
                true,
                true,
                100_000_000L,
                10_000L,
                5_000L,
                200L,
                500L,
                1_000_000_000L,
                300_000L,
                250_000_000L,
                8,
                100L,
                3_000L,
                -3_000L,
                10_000_000L,
                3,
                InstrumentStatus.TRADING,
                updatedAt,
                updatedAt.minusSeconds(60),
                updatedAt,
                List.of(),
                List.of());
    }
}
