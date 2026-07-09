package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminAccountAssetReportControllerTest {

    @Test
    void valuationAggregatesPricedRowsAndReportsMissingRates() {
        AuthService authService = mock(AuthService.class);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.parse("2026-07-03T00:00:00Z"));
        when(authService.requireAdminPermission("Bearer admin", "admin.reports.read")).thenReturn(principal);
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminAccountAssetReportController controller = new AdminAccountAssetReportController(
                authService, jdbcTemplate, new AdminAccountAssetSnapshotService(jdbcTemplate));

        var response = controller.valuation("Bearer admin", "usdt", null, null, null, true,
                50, null, "valuationValue.desc");

        assertThat(response.valuationAsset()).isEqualTo("USDT");
        assertThat(response.totals().rows()).isEqualTo(2);
        assertThat(response.totals().uniqueUsers()).isEqualTo(2);
        assertThat(response.totals().pricedRows()).isEqualTo(1);
        assertThat(response.totals().missingRateRows()).isEqualTo(1);
        assertThat(response.totals().totalValue()).isEqualByComparingTo("1000.00");
        assertThat(response.warnings()).singleElement()
                .extracting(AdminAccountAssetReportController.AccountAssetReportWarning::area)
                .isEqualTo("valuation");
        assertThat(jdbcTemplate.lastQuerySql).contains("FROM account_balances");
        verify(authService).requireAdminPermission("Bearer admin", "admin.reports.read");
    }

    @Test
    void valuationReturnsCursorPage() {
        AuthService authService = mock(AuthService.class);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.parse("2026-07-03T00:00:00Z"));
        when(authService.requireAdminPermission("Bearer admin", "admin.reports.read")).thenReturn(principal);
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminAccountAssetReportController controller = new AdminAccountAssetReportController(
                authService, jdbcTemplate, new AdminAccountAssetSnapshotService(jdbcTemplate));

        var response = controller.valuation("Bearer admin", "USDT", null, null, null, true,
                1, null, "valuationValue.desc");

        assertThat(response.rows()).singleElement()
                .extracting(AdminAccountAssetReportController.AccountAssetValuationRow::userId)
                .isEqualTo(1001L);
        assertThat(response.totals().rows()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(response.sort()).isEqualTo("valuationValue.desc");
        assertThat(response.limit()).isEqualTo(1);
        assertThat(jdbcTemplate.lastQuerySql)
                .contains("ORDER BY valuation_value DESC NULLS LAST, updated_at DESC, user_id DESC");
        verify(authService).requireAdminPermission("Bearer admin", "admin.reports.read");
    }

    @Test
    void valuationAcceptsDeliveryAndOptionAccountTypes() {
        AuthService authService = mock(AuthService.class);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.parse("2026-07-03T00:00:00Z"));
        when(authService.requireAdminPermission("Bearer admin", "admin.reports.read")).thenReturn(principal);
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminAccountAssetReportController controller = new AdminAccountAssetReportController(
                authService, jdbcTemplate, new AdminAccountAssetSnapshotService(jdbcTemplate));

        controller.valuation("Bearer admin", "USDT", null, "coin_delivery", null, true,
                50, null, "valuationValue.desc");

        assertThat(jdbcTemplate.lastQueryArgs).contains("COIN_DELIVERY");

        controller.valuation("Bearer admin", "USDT", null, "option", null, true,
                50, null, "valuationValue.desc");

        assertThat(jdbcTemplate.lastQueryArgs).contains("OPTION");
        verify(authService, times(2)).requireAdminPermission("Bearer admin", "admin.reports.read");
    }

    @Test
    void createSnapshotWritesReportRowsAndReturnsStoredSnapshot() {
        AuthService authService = mock(AuthService.class);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.parse("2026-07-03T00:00:00Z"));
        when(authService.requireAdminPermission("Bearer admin", "admin.reports.write")).thenReturn(principal);
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminAccountAssetReportController controller = new AdminAccountAssetReportController(
                authService, jdbcTemplate, new AdminAccountAssetSnapshotService(jdbcTemplate));

        var response = controller.createSnapshot("Bearer admin", "USDT", "2026-07-03");

        assertThat(response.snapshotDate()).isEqualTo(LocalDate.parse("2026-07-03"));
        assertThat(response.valuationAsset()).isEqualTo("USDT");
        assertThat(response.writtenRows()).isEqualTo(2);
        assertThat(response.snapshots()).singleElement().satisfies(row -> {
            assertThat(row.accountType()).isEqualTo("BASIC");
            assertThat(row.asset()).isEqualTo("USDT");
            assertThat(row.totalValue()).isEqualByComparingTo("1000.00");
            assertThat(row.createdByUserId()).isEqualTo(7L);
        });
        assertThat(jdbcTemplate.lastUpdateSql).contains("INSERT INTO gateway_admin_account_asset_snapshots");
        verify(authService).requireAdminPermission("Bearer admin", "admin.reports.write");
    }

    @Test
    void snapshotsReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.parse("2026-07-03T00:00:00Z"));
        when(authService.requireAdminPermission("Bearer admin", "admin.reports.read")).thenReturn(principal);
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminAccountAssetReportController controller = new AdminAccountAssetReportController(
                authService, jdbcTemplate, new AdminAccountAssetSnapshotService(jdbcTemplate));

        var response = controller.snapshots("Bearer admin", "2026-07-03", "USDT", null, null,
                1, null, "snapshotDate.desc");

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(response.sort()).isEqualTo("snapshotDate.desc");
        assertThat(response.limit()).isEqualTo(1);
        assertThat(response.snapshots()).singleElement().satisfies(row -> {
            assertThat(row.snapshotId()).isEqualTo(99L);
            assertThat(row.totalValue()).isEqualByComparingTo("1000.00");
        });
        assertThat(jdbcTemplate.lastQuerySql)
                .contains("ORDER BY snapshot_date DESC, total_value DESC NULLS LAST, snapshot_id DESC");
        verify(authService).requireAdminPermission("Bearer admin", "admin.reports.read");
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private String lastQuerySql;
        private Object[] lastQueryArgs = new Object[0];
        private String lastUpdateSql;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.lastQuerySql = sql;
            this.lastQueryArgs = args == null ? new Object[0] : args;
            if (sql.contains("FROM gateway_admin_account_asset_snapshots")) {
                List<Map<String, Object>> rows = List.of(row(
                        "snapshot_id", 99L,
                        "snapshot_date", Date.valueOf("2026-07-03"),
                        "valuation_asset", "USDT",
                        "account_type", "BASIC",
                        "asset", "USDT",
                        "total_available_units", new BigDecimal("100000000000"),
                        "total_locked_units", BigDecimal.ZERO,
                        "total_equity_units", new BigDecimal("100000000000"),
                        "valuation_rate", BigDecimal.ONE,
                        "total_value", new BigDecimal("1000.00"),
                        "valuation_source", "PAR",
                        "rate_updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")),
                        "source_updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")),
                        "user_count", 1L,
                        "balance_count", 1L,
                        "created_by_user_id", 7L,
                        "created_by_username", "admin",
                        "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:01Z"))),
                        row(
                                "snapshot_id", 98L,
                                "snapshot_date", Date.valueOf("2026-07-03"),
                                "valuation_asset", "USDT",
                                "account_type", "FUNDING",
                                "asset", "BTC",
                                "total_available_units", new BigDecimal("100000000"),
                                "total_locked_units", BigDecimal.ZERO,
                                "total_equity_units", new BigDecimal("100000000"),
                                "valuation_rate", new BigDecimal("50000.00"),
                                "total_value", new BigDecimal("500.00"),
                                "valuation_source", "MARK",
                                "rate_updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")),
                                "source_updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")),
                                "user_count", 1L,
                                "balance_count", 1L,
                                "created_by_user_id", 7L,
                                "created_by_username", "admin",
                                "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:01Z"))));
                Object lastArg = args.length == 0 ? null : args[args.length - 1];
                return lastArg instanceof Number number && number.intValue() == 2 ? rows : rows.subList(0, 1);
            }
            return List.of(
                    row(
                            "account_type", "BASIC",
                            "user_id", 1001L,
                            "asset", "USDT",
                            "available_units", 100_000_000_000L,
                            "locked_units", 0L,
                            "equity_units", 100_000_000_000L,
                            "scale_units", 100_000_000L,
                            "valuation_rate", BigDecimal.ONE,
                            "valuation_value", new BigDecimal("1000.00"),
                            "valuation_source", "PAR",
                            "rate_updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")),
                            "updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z"))),
                    row(
                            "account_type", "FUNDING",
                            "user_id", 1002L,
                            "asset", "DOGE",
                            "available_units", 1_000_000L,
                            "locked_units", 0L,
                            "equity_units", 1_000_000L,
                            "scale_units", 100_000_000L,
                            "valuation_rate", null,
                            "valuation_value", null,
                            "valuation_source", "MISSING",
                            "rate_updated_at", null,
                            "updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z"))));
        }

        @Override
        public int update(String sql, Object... args) {
            this.lastUpdateSql = sql;
            return 2;
        }

        private static Map<String, Object> row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i += 2) {
                row.put(String.valueOf(values[i]), values[i + 1]);
            }
            return row;
        }
    }
}
