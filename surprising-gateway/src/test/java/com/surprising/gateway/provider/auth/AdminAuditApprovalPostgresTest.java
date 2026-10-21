package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.repository.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalCreateRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实初始化表验证审计持久化、双人审批和一次性消费；每次独占 schema。 */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class AdminAuditApprovalPostgresTest {
    @Test
    void persistsAuditAndEnforcesApprovalIdentityRequestAndSingleConsumption() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER");
        String password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        String schema = "admin_audit_it_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema, user, password);
            var jdbc = new JdbcTemplate(source);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
            jdbc.update("INSERT INTO gateway_users VALUES (101), (102)");
            String init = Files.readString(Path.of("../init.sql"));
            for (String table : List.of("gateway_admin_operation_logs", "gateway_admin_approval_requests")) {
                int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
                jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
            }
            var approvals = new AdminApprovalRepository(jdbc);
            var audits = new AdminAuditRepository(jdbc);
            Instant now = Instant.parse("2026-10-21T06:00:00Z");
            var requester = new JwtPrincipal(101, "requester", "NORMAL", List.of("ADMIN"), now.plusSeconds(3600));
            var approver = new JwtPrincipal(102, "approver", "NORMAL", List.of("ADMIN"), now.plusSeconds(3600));
            String hash = "a".repeat(64);
            String path = "/api/v1/admin/accounts/adjustments";
            var created = approvals.create(requester,
                    new AdminApprovalCreateRequest("account", "POST", path, null, hash, "余额调整"),
                    Duration.ofMinutes(30), now);
            assertThatThrownBy(() -> transactions.execute(tx -> approvals.approve(created.approvalId(), requester, "self", now)))
                    .hasMessageContaining("different administrator");
            transactions.executeWithoutResult(tx -> approvals.approve(created.approvalId(), approver, "同意", now));
            assertThatThrownBy(() -> transactions.execute(tx -> approvals.consumeApproved(created.approvalId(), 102,
                    "account", "POST", path, null, hash, now))).hasMessageContaining("requester does not match");
            assertThatThrownBy(() -> transactions.execute(tx -> approvals.consumeApproved(created.approvalId(), 101,
                    "account", "POST", path, null, "b".repeat(64), now))).hasMessageContaining("does not match this request");
            var consumed = transactions.execute(tx -> approvals.consumeApproved(created.approvalId(), 101,
                    "account", "POST", path, null, hash, now));
            assertThat(consumed.status()).isEqualTo("CONSUMED");
            assertThat(consumed.approverUserId()).isEqualTo(102);
            assertThat(consumed.consumedAt()).isEqualTo(now);
            assertThatThrownBy(() -> transactions.execute(tx -> approvals.consumeApproved(created.approvalId(), 101,
                    "account", "POST", path, null, hash, now))).hasMessageContaining("not approved");
            assertThat(approvals.approvals("CONSUMED", 101L, 102L, "account", 10)).containsExactly(consumed);

            audits.recordRequired(new AdminOperationRecord(101L, "requester", List.of("ADMIN"), "account", "POST",
                    path, "referenceId=adjustment-1", "LINEAR_PERPETUAL", hash, 200, 7L, true, null,
                    "qa", "127.0.0.1", now));
            var row = audits.operationLogPage(101L, "account", "POST", true,
                    new com.surprising.gateway.provider.auth.model.CreatedAtPageQuery(10,null,true)).items().getFirst();
            assertThat(row.requestBodySha256()).isEqualTo(hash);
            assertThat(row.queryString()).isEqualTo("referenceId=adjustment-1");
            assertThat(row.adminRoles()).containsExactly("ADMIN");
            assertThat(row.targetUri()).isEqualTo("LINEAR_PERPETUAL");
            assertThat(row.responseStatus()).isEqualTo(200);
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
