package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AdminQueryTaskServiceTest {

    @Test
    void createRunsOperationLatencyQueryAndStoresJsonResult() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, jdbcTemplate, new ObjectMapper(), Runnable::run);
        JwtPrincipal principal = principal();
        AdminQueryTaskCreateRequest request = new AdminQueryTaskCreateRequest(
                "SYSTEM_OPERATION_LATENCY", Map.of("service", "account", "windowMinutes", "60", "limit", "10"));

        AdminQueryTaskResponse response = service.create(principal, request, Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(response.queryTaskId()).isEqualTo(91L);
        assertThat(repository.archivedExpired).isTrue();
        assertThat(repository.running).isTrue();
        assertThat(repository.succeeded).isTrue();
        assertThat(repository.rowCount).isEqualTo(1);
        assertThat(repository.resultJson)
                .contains("\"queryType\":\"SYSTEM_OPERATION_LATENCY\"")
                .contains("\"service\":\"account\"")
                .contains("\"p95_duration_ms\":180");
        assertThat(jdbcTemplate.sql).contains("FROM gateway_admin_operation_logs");
    }

    @Test
    void invalidAlertDeliveryStatusMarksTaskFailed() {
        FakeRepository repository = new FakeRepository();
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, new FakeJdbcTemplate(), new ObjectMapper(), Runnable::run);
        AdminQueryTaskCreateRequest request = new AdminQueryTaskCreateRequest(
                "ALERT_DELIVERY_FAILURES", Map.of("status", "PENDING"));

        service.create(principal(), request, Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(repository.failed).isTrue();
        assertThat(repository.errorMessage).contains("status must be FAILED or SKIPPED");
    }

    @Test
    void createRunsOrderAuditQueryAndStoresJsonResult() {
        FakeRepository repository = new FakeRepository();
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, jdbcTemplate, new ObjectMapper(), Runnable::run);
        AdminQueryTaskCreateRequest request = new AdminQueryTaskCreateRequest(
                "ORDER_AUDIT_SEARCH",
                Map.of("userId", "42", "symbol", "BTC-USDT", "createdAfter", "2026-07-03T00:00:00Z"));

        service.create(principal(), request, Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(repository.succeeded).isTrue();
        assertThat(repository.resultJson)
                .contains("\"queryType\":\"ORDER_AUDIT_SEARCH\"")
                .contains("\"table\":\"trading_orders\"")
                .contains("\"order_id\":10001");
        assertThat(jdbcTemplate.sql).contains("FROM trading_orders");
    }

    @Test
    void createRejectsWhenActiveUserQuotaExceeded() {
        FakeRepository repository = new FakeRepository();
        repository.activeForUser = 3L;
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, new FakeJdbcTemplate(), new ObjectMapper(), Runnable::run);
        AdminQueryTaskCreateRequest request = new AdminQueryTaskCreateRequest(
                "OUTBOX_BACKLOG", Map.of());

        assertThatThrownBy(() -> service.create(principal(), request, Instant.parse("2026-07-03T00:00:00Z")))
                .isInstanceOf(AdminQueryTaskService.QueryTaskQuotaExceededException.class)
                .hasMessageContaining("active query task quota exceeded");
    }

    @Test
    void limitsExposeQuotaAndStorageState() {
        FakeRepository repository = new FakeRepository();
        repository.activeForUser = 1L;
        repository.activeGlobal = 4L;
        repository.createdForUserSince = 8L;
        repository.retainedResultBytes = 2048L;
        repository.expiredReadyToArchive = 2L;
        GatewayProperties.QueryTasks properties = new GatewayProperties.QueryTasks();
        properties.setMaxActivePerUser(5);
        properties.setMaxActiveGlobal(50);
        properties.setMaxCreatedPerUserInWindow(30);
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, new FakeJdbcTemplate(), new ObjectMapper(), Runnable::run, properties);

        var limits = service.limits(principal(), Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(limits.activeTasksForUser()).isEqualTo(1);
        assertThat(limits.maxActiveTasksPerUser()).isEqualTo(5);
        assertThat(limits.activeTasksGlobal()).isEqualTo(4);
        assertThat(limits.createdByUserInWindow()).isEqualTo(8);
        assertThat(limits.retainedResultBytes()).isEqualTo(2048);
        assertThat(limits.expiredTasksReadyToArchive()).isEqualTo(2);
    }

    @Test
    void archiveExpiredCanUseManualAgeCutoff() {
        FakeRepository repository = new FakeRepository();
        repository.archiveFinishedBeforeCount = 11;
        AdminQueryTaskService service = new AdminQueryTaskService(
                repository, new FakeJdbcTemplate(), new ObjectMapper(), Runnable::run);

        var response = service.archiveExpired(
                new AdminQueryTaskArchiveRequest(7, 250, "manual weekly cleanup"),
                Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(response.archivedCount()).isEqualTo(11);
        assertThat(response.archivedBefore()).isEqualTo(Instant.parse("2026-06-26T00:00:00Z"));
        assertThat(response.limit()).isEqualTo(250);
        assertThat(response.reason()).isEqualTo("manual weekly cleanup");
        assertThat(repository.archiveFinishedBefore).isTrue();
    }

    private JwtPrincipal principal() {
        return new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60));
    }

    private static final class FakeRepository extends AdminQueryTaskRepository {
        private boolean running;
        private boolean succeeded;
        private boolean failed;
        private String resultJson;
        private int rowCount;
        private String errorMessage;
        private boolean archivedExpired;
        private boolean archiveFinishedBefore;
        private long activeForUser;
        private long activeGlobal;
        private long createdForUserSince;
        private long retainedResultBytes;
        private long expiredReadyToArchive;
        private int archiveFinishedBeforeCount;

        private FakeRepository() {
            super(null);
        }

        @Override
        public AdminQueryTaskResponse create(JwtPrincipal principal, String queryType, String queryParams, Instant now) {
            return new AdminQueryTaskResponse(91L, principal.userId(), principal.username(), queryType,
                    "PENDING", queryParams, null, 0, 0, null,
                    now, null, null, now.plus(AdminQueryTaskRepository.DEFAULT_EXPIRY),
                    null, null);
        }

        @Override
        public AdminQueryTaskResponse markRunning(long queryTaskId, Instant now) {
            this.running = true;
            return null;
        }

        @Override
        public AdminQueryTaskResponse markSucceeded(long queryTaskId, String resultJson, int rowCount, Instant now) {
            this.succeeded = true;
            this.resultJson = resultJson;
            this.rowCount = rowCount;
            return null;
        }

        @Override
        public AdminQueryTaskResponse markFailed(long queryTaskId, String errorMessage, Instant now) {
            this.failed = true;
            this.errorMessage = errorMessage;
            return null;
        }

        @Override
        public long countActiveForUser(long userId) {
            return activeForUser;
        }

        @Override
        public long countActiveGlobal() {
            return activeGlobal;
        }

        @Override
        public long countCreatedForUserSince(long userId, Instant since) {
            return createdForUserSince;
        }

        @Override
        public long retainedResultBytes() {
            return retainedResultBytes;
        }

        @Override
        public long countExpiredReadyToArchive(Instant now) {
            return expiredReadyToArchive;
        }

        @Override
        public int archiveExpired(Instant now, int limit, String reason) {
            this.archivedExpired = true;
            return 0;
        }

        @Override
        public int archiveFinishedBefore(Instant cutoff, Instant now, int limit, String reason) {
            this.archiveFinishedBefore = true;
            return archiveFinishedBeforeCount;
        }
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private String sql;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            if (sql.contains("FROM trading_orders")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("order_id", 10001L);
                row.put("user_id", 42L);
                row.put("client_order_id", "client-a");
                row.put("symbol", "BTC-USDT");
                row.put("status", "FILLED");
                row.put("created_at", Instant.parse("2026-07-03T00:00:01Z"));
                return List.of(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", "account");
            row.put("total", 100L);
            row.put("failed", 2L);
            row.put("failure_rate_ppm", 20_000L);
            row.put("p50_duration_ms", 70L);
            row.put("p95_duration_ms", 180L);
            row.put("p99_duration_ms", 260L);
            row.put("last_seen_at", Instant.parse("2026-07-03T00:00:00Z"));
            return List.of(row);
        }
    }
}
