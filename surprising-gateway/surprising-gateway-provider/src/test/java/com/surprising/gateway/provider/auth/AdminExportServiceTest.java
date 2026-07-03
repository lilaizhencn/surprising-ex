package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.auth.AuthModels.AdminExportCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AdminExportServiceTest {

    @Test
    void createRunsExportAndStoresEscapedCsv() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminExportService service = new AdminExportService(repository, jdbcTemplate,
                new ObjectMapper(), Runnable::run);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.now().plusSeconds(60));
        AdminExportCreateRequest request = new AdminExportCreateRequest("USERS",
                Map.of("query", "alice", "limit", "1"));

        AdminExportJobResponse response = service.create(principal, request, Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(response.exportId()).isEqualTo(88L);
        assertThat(repository.running).isTrue();
        assertThat(repository.succeeded).isTrue();
        assertThat(repository.rowCount).isEqualTo(1);
        assertThat(repository.csv).contains("user_id,username,email,status,roles,created_at");
        assertThat(repository.csv).contains("42,\"alice,ops\",alice@example.com,NORMAL,USER");
        assertThat(repository.fileName).startsWith("users-").endsWith(".csv");
        assertThat(jdbcTemplate.sql).contains("FROM gateway_users");
    }

    @Test
    void createRunsOrderExportFromFixedTradingTable() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminExportService service = new AdminExportService(repository, jdbcTemplate,
                new ObjectMapper(), Runnable::run);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.now().plusSeconds(60));
        AdminExportCreateRequest request = new AdminExportCreateRequest("ORDERS",
                Map.of("symbol", "btc-usdt", "status", "filled", "limit", "10"));

        service.create(principal, request, Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(repository.succeeded).isTrue();
        assertThat(repository.csv).contains("order_id,user_id,client_order_id,symbol");
        assertThat(repository.csv).contains("9001,42,client-1,BTC-USDT");
        assertThat(repository.fileName).startsWith("orders-").endsWith(".csv");
        assertThat(jdbcTemplate.sql).contains("FROM trading_orders");
    }

    @Test
    void createRunsAccountAdjustmentsExportFromFixedAuditTable() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminExportService service = new AdminExportService(repository, jdbcTemplate,
                new ObjectMapper(), Runnable::run);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.now().plusSeconds(60));
        AdminExportCreateRequest request = new AdminExportCreateRequest("ACCOUNT_ADJUSTMENTS",
                Map.of("adminUserId", "7", "userId", "42", "adjustmentKind", "product", "asset", "usdt",
                        "limit", "10"));

        service.create(principal, request, Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(repository.succeeded).isTrue();
        assertThat(repository.csv).contains("adjustment_id,adjustment_kind,admin_user_id,admin_username");
        assertThat(repository.csv).contains("55,PRODUCT,7,admin,42,FUNDING,USDT,1000,2500,manual-1,MANUAL_CREDIT");
        assertThat(repository.fileName).startsWith("account_adjustments-").endsWith(".csv");
        assertThat(jdbcTemplate.sql).contains("FROM account_admin_balance_adjustments");
    }

    @Test
    void adminOperationExportIncludesDurationMillis() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminExportService service = new AdminExportService(repository, jdbcTemplate,
                new ObjectMapper(), Runnable::run);
        JwtPrincipal principal = new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                Instant.now().plusSeconds(60));
        AdminExportCreateRequest request = new AdminExportCreateRequest("ADMIN_OPERATIONS",
                Map.of("service", "account", "limit", "10"));

        service.create(principal, request, Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(repository.succeeded).isTrue();
        assertThat(repository.csv).contains("response_status,duration_ms,success");
        assertThat(repository.csv).contains("200,145,true");
        assertThat(jdbcTemplate.sql).contains("duration_ms").contains("FROM gateway_admin_operation_logs");
    }

    private static final class FakeRepository extends AdminExportRepository {
        private boolean running;
        private boolean succeeded;
        private int rowCount;
        private String csv;
        private String fileName;

        private FakeRepository() {
            super(null);
        }

        @Override
        public AdminExportJobResponse create(JwtPrincipal principal, String exportType, String queryParams, Instant now) {
            return new AdminExportJobResponse(88L, principal.userId(), principal.username(), exportType,
                    "PENDING", "CSV", queryParams, null, null, 0, 0, null,
                    now, null, null, now.plus(AdminExportRepository.DEFAULT_EXPIRY));
        }

        @Override
        public AdminExportJobResponse markRunning(long exportId, Instant now) {
            this.running = true;
            return null;
        }

        @Override
        public AdminExportJobResponse markSucceeded(long exportId,
                                                    String fileName,
                                                    String contentType,
                                                    int rowCount,
                                                    String csv,
                                                    Instant now) {
            this.succeeded = true;
            this.rowCount = rowCount;
            this.csv = csv;
            this.fileName = fileName;
            return null;
        }
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private String sql;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            if (sql.contains("FROM account_admin_balance_adjustments")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("adjustment_id", 55L);
                row.put("adjustment_kind", "PRODUCT");
                row.put("admin_user_id", 7L);
                row.put("admin_username", "admin");
                row.put("user_id", 42L);
                row.put("account_type", "FUNDING");
                row.put("asset", "USDT");
                row.put("amount_units", 1_000L);
                row.put("balance_after_units", 2_500L);
                row.put("reference_id", "manual-1");
                row.put("reason", "MANUAL_CREDIT");
                row.put("created_at", Instant.parse("2026-07-02T00:00:00Z"));
                return List.of(row);
            }
            if (sql.contains("FROM trading_orders")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("order_id", 9001L);
                row.put("user_id", 42L);
                row.put("client_order_id", "client-1");
                row.put("symbol", "BTC-USDT");
                row.put("instrument_version", 1L);
                row.put("side", "BUY");
                row.put("order_type", "LIMIT");
                row.put("time_in_force", "GTC");
                row.put("price_ticks", 6200000L);
                row.put("quantity_steps", 100L);
                row.put("executed_quantity_steps", 100L);
                row.put("remaining_quantity_steps", 0L);
                row.put("margin_mode", "CROSS");
                row.put("maker_fee_rate_ppm", 100L);
                row.put("taker_fee_rate_ppm", 200L);
                row.put("reduce_only", false);
                row.put("post_only", false);
                row.put("status", "FILLED");
                row.put("reject_reason", null);
                row.put("created_at", Instant.parse("2026-07-02T00:00:00Z"));
                row.put("updated_at", Instant.parse("2026-07-02T00:00:01Z"));
                return List.of(row);
            }
            if (sql.contains("FROM gateway_admin_operation_logs")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("operation_id", 101L);
                row.put("admin_user_id", 7L);
                row.put("admin_username", "admin");
                row.put("admin_roles", "ADMIN");
                row.put("service", "account");
                row.put("http_method", "GET");
                row.put("request_path", "/api/v1/admin/gateway/account/balances");
                row.put("query_string", "userId=42");
                row.put("target_uri", "http://account/api/v1/admin/accounts/balances?userId=42");
                row.put("request_body_sha256", null);
                row.put("response_status", 200);
                row.put("duration_ms", 145L);
                row.put("success", true);
                row.put("error_message", null);
                row.put("trace_id", "trace-1");
                row.put("ip_address", "127.0.0.1");
                row.put("created_at", Instant.parse("2026-07-02T00:00:00Z"));
                return List.of(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_id", 42L);
            row.put("username", "alice,ops");
            row.put("email", "alice@example.com");
            row.put("status", "NORMAL");
            row.put("roles", "USER");
            row.put("created_at", Instant.parse("2026-07-02T00:00:00Z"));
            return List.of(row);
        }
    }
}
