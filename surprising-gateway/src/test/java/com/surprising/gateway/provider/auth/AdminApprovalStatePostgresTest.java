package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.controller.AdminAuthController;
import com.surprising.gateway.provider.auth.model.AdminApprovalCommand;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** Spring service transaction + actual row lock and PostgreSQL state, through HTTP where applicable. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class AdminApprovalStatePostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions {
    }
    private static final String PATH = "/api/v1/admin/accounts/adjustments";
    private static final String HASH = "a".repeat(64);
    private JdbcTemplate admin, jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private AdminApprovalRepository rows;
    private AdminApprovalService service;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "approval_state_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO gateway_users VALUES(101),(102)");
        String init = Files.readString(Path.of("../init.sql"));
        int start = init.indexOf("CREATE TABLE IF NOT EXISTS gateway_admin_approval_requests (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        rows = spy(new AdminApprovalRepository(jdbc));
        var auth = mock(AuthService.class);
        for (long id : List.of(101L, 102L)) {
            when(auth.authenticateAdminBearer("Bearer " + id)).thenReturn(
                    new JwtPrincipal(id, "admin" + id, "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600)));
        }
        var properties = new GatewayProperties();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(AdminApprovalService.class, () -> new AdminApprovalService(auth, mock(AdminAuditRepository.class), rows, properties));
        context.refresh();
        service = context.getBean(AdminApprovalService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuthController(auth, service, json)).build();
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void httpCreateBindsNormalizedCommandAndRejectsSelfApproval() throws Exception {
        String body = json.writeValueAsString(new AdminApprovalCommand(" ACCOUNT ", " post ", " " + PATH + " ", " id=7 ", HASH.toUpperCase(), " review "));
        var response = mvc.perform(post("/api/v1/admin/approvals").header("Authorization", "Bearer 101")
                .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse();
        var created = json.readTree(response.getContentAsByteArray());
        long id = created.path("approvalId").asLong();
        assertThat(created.path("service").asString()).isEqualTo("account");
        assertThat(created.path("httpMethod").asString()).isEqualTo("POST");
        assertThat(created.path("requestPath").asString()).isEqualTo(PATH);
        assertThat(created.path("queryString").asString()).isEqualTo("id=7");
        assertThat(created.path("reason").asString()).isEqualTo("review");
        assertThat(created.path("requestBodySha256").asString()).isEqualTo(HASH);
        mvc.perform(post("/api/v1/admin/approvals/" + id + "/approve").header("Authorization", "Bearer 101"))
                .andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("PENDING");
        mvc.perform(post("/api/v1/admin/approvals/" + id + "/approve").header("Authorization", "Bearer 102")
                .contentType("application/json").content("{\"reason\":\"   \"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT decision_reason IS NULL FROM gateway_admin_approval_requests WHERE approval_id=?", Boolean.class, id)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"service", "method", "path", "hash", "reason"})
    void malformedCreationIsRejectedBeforePersistence(String invalid) throws Exception {
        var command = new AdminApprovalCommand(invalid.equals("service") ? "bad/path" : "account",
                invalid.equals("method") ? "PO ST" : "POST", invalid.equals("path") ? "/user/private" : PATH,
                null, invalid.equals("hash") ? "bad-hash" : HASH, invalid.equals("reason") ? " " : "review");
        mvc.perform(post("/api/v1/admin/approvals").header("Authorization", "Bearer 101").contentType("application/json")
                .content(json.writeValueAsString(command))).andExpect(status().isBadRequest());
        verifyNoInteractions(rows);
    }

    @Test void expiredAndTerminalApprovalsCannotBeDecidedAgain() {
        long id = create();
        jdbc.update("UPDATE gateway_admin_approval_requests SET expires_at=now()-interval '1 minute' WHERE approval_id=?", id);
        assertThatThrownBy(() -> service.approve("Bearer 102", id, null)).hasMessageContaining("expired");
        assertThat(state(id)).isEqualTo("PENDING");
        long rejected = create();
        assertThat(service.reject("Bearer 102", rejected, "reject").status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> service.approve("Bearer 102", rejected, null)).hasMessageContaining("not pending");
        assertThatThrownBy(() -> consume(rejected)).hasMessageContaining("not approved");
    }

    @ParameterizedTest @ValueSource(strings = {"requester", "service", "method", "path", "query", "hash", "expiry"})
    void mismatchedOrExpiredRequestCannotConsumeApproval(String mismatch) {
        long id = approved();
        if (mismatch.equals("expiry")) jdbc.update("UPDATE gateway_admin_approval_requests SET expires_at=now()-interval '1 minute' WHERE approval_id=?", id);
        assertThatThrownBy(() -> service.consumeApproved(id, mismatch.equals("requester") ? 102 : 101,
                mismatch.equals("service") ? "wallet" : "account", mismatch.equals("method") ? "DELETE" : "POST",
                mismatch.equals("path") ? PATH + "/other" : PATH, mismatch.equals("query") ? "id=8" : "id=7",
                mismatch.equals("hash") ? "b".repeat(64) : HASH, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("APPROVED");
        verify(rows, never()).markConsumed(anyLong(), any());
    }

    @Test void consumptionUsesServiceTransactionAndCanRetryAfterDatabaseFailure() {
        long id = approved();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return call.callRealMethod();
        }).when(rows).findForUpdate(id);
        jdbc.execute("ALTER TABLE gateway_admin_approval_requests ADD CONSTRAINT fail_consume CHECK(status<>'CONSUMED') NOT VALID");
        try {
            assertThatThrownBy(() -> consume(id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(state(id)).isEqualTo("APPROVED");
        } finally {
            jdbc.execute("ALTER TABLE gateway_admin_approval_requests DROP CONSTRAINT fail_consume");
        }
        assertThat(consume(id).status()).isEqualTo("CONSUMED");
        assertThatThrownBy(() -> consume(id)).hasMessageContaining("not approved");
    }

    @Test void concurrentConsumptionLocksRowAndOnlyOneRequestSucceeds() throws Exception {
        long id = approved();
        var held = new CountDownLatch(1);
        var secondAttempt = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstReader = new AtomicBoolean(true);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (!firstReader.compareAndSet(true, false)) {
                secondAttempt.countDown();
                return call.callRealMethod();
            }
            Object result = call.callRealMethod();
            held.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(rows).findForUpdate(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AdminApprovalResponse> first = executor.submit(() -> consume(id));
            try {
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                Future<AdminApprovalResponse> second = executor.submit(() -> consume(id));
                assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.isDone()).isFalse();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("CONSUMED");
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("not approved");
            } finally {
                release.countDown();
            }
        }
        assertThat(state(id)).isEqualTo("CONSUMED");
        verify(rows, times(1)).markConsumed(eq(id), any());
    }

    private long create() {
        return service.create("Bearer 101", new AdminApprovalCommand("account", "POST", PATH, "id=7", HASH, "review")).approvalId();
    }
    private long approved() {
        long id = create();
        service.approve("Bearer 102", id, null);
        return id;
    }
    private AdminApprovalResponse consume(long id) {
        return service.consumeApproved(id, 101, "account", "POST", PATH, "id=7", HASH, Instant.now());
    }
    private String state(long id) {
        return jdbc.queryForObject("SELECT status FROM gateway_admin_approval_requests WHERE approval_id=?", String.class, id);
    }
}
