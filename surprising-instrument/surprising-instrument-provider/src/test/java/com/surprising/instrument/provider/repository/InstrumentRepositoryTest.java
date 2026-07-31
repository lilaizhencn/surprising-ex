package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
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

        List<InstrumentVersionKey> keys = rows.stream()
                .map(row -> new InstrumentVersionKey(row.symbol(), row.version()))
                .toList();
        InstrumentRepository.InstrumentPage page =
                repository.listPage(keys, null, InstrumentStatus.TRADING, 2, null, "updatedAt.desc");

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
        assertThat(args.getValue()).containsExactly(
                "ETH-USDT", 2L, "BTC-USDT", 3L, "ADA-USDT", 1L, "TRADING", 3);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void versionsPageCanFilterByProductLine() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        repository.versionsPage("BTC-USDT", ProductLine.OPTION, 10, null, "version.desc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("contract_type = ?");
        assertThat(args.getValue()).containsExactly("BTC-USDT", "VANILLA_OPTION", 11);
    }

    @Test
    void listPageRejectsUnsupportedSort() {
        assertThatThrownBy(() -> repository.listPage(
                List.of(new InstrumentVersionKey("BTC-USDT", 1L)),
                null, null, 10, null, "status.desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported sort");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void expiringContractsDueScansCurrentDeliveryAndOptionInstruments() {
        Instant now = Instant.parse("2026-03-27T08:00:00Z");

        repository.expiringContractsDue(
                List.of(new InstrumentVersionKey("BTC-USDT-260327", 4L)), now, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("i.instrument_type IN ('DELIVERY', 'OPTION')")
                .contains("i.status IN ('PRE_TRADING', 'TRADING', 'HALT')")
                .contains("i.expiry_time <= ?")
                .doesNotContain("instrument_product_current_versions");
        assertThat(args.getValue()).containsExactly(
                Timestamp.from(now), "BTC-USDT-260327", 4L, 25);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void settlingContractsDueScansCurrentSettlingContracts() {
        Instant now = Instant.parse("2026-03-27T08:05:00Z");

        repository.settlingContractsDue(
                List.of(new InstrumentVersionKey("BTC-USDT-260327", 4L)), now, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("i.instrument_type IN ('DELIVERY', 'OPTION')")
                .contains("i.status = 'SETTLING'")
                .contains("i.delivery_time <= ?")
                .doesNotContain("instrument_product_current_versions");
        assertThat(args.getValue()).containsExactly(
                Timestamp.from(now), "BTC-USDT-260327", 4L, 25);
    }

    @Test
    void insertPersistsExpiringOptionMetadata() {
        Instant expiryTime = Instant.parse("2026-03-27T08:00:00Z");
        Instant deliveryTime = expiryTime.plusSeconds(300);
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                "BTC-USDT-260327-50000-C", InstrumentType.OPTION, ContractType.VANILLA_OPTION,
                "BTC", "USDT", "USDT", 1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT"), List.of("GTC", "IOC"), true, true, false,
                100_000_000L, 10_000L, 5_000L, 200L, 500L, 500_000_000_000_000L,
                300_000L, 25_000_000_000_000L, 0, 0L, 0L, 0L, 1_000_000_000_000L,
                2, expiryTime, deliveryTime, "btc-usdt", 50_000_000_000L, OptionType.CALL,
                OptionExerciseStyle.EUROPEAN, ContractSettlementMethod.CASH, InstrumentStatus.PRE_TRADING,
                null, List.of(), List.of(new IndexSourceConfig("A", true, "https://example.com",
                "/ticker", "BTCUSDT", "BINANCE_BOOK_TICKER", "USDT", "USDT", null, null, null,
                "DISCOUNT", "MULTIPLY", 500_000L, false, null, null, null, 1_000_000L)));

        repository.insert("BTC-USDT-260327-50000-C", 2L, request, Instant.parse("2026-01-01T00:00:00Z"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).contains(Timestamp.from(expiryTime), Timestamp.from(deliveryTime),
                "BTC-USDT", 50_000_000_000L, "CALL", "EUROPEAN", "CASH");
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                InstrumentStatus.TRADING,
                updatedAt,
                updatedAt.minusSeconds(60),
                updatedAt,
                List.of(),
                List.of());
    }
}
