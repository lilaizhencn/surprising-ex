package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.client.*;
import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AdminApprovalCommand;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.model.ComplianceModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual controller binding, approval consumption and compliance business transaction. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class ComplianceWritePostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions {
    }
    private JdbcTemplate admin, jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private ComplianceService service;
    private AdminApprovalService approvals;
    private ComplianceKycRepository profiles;
    private ComplianceRiskTagRepository tags;
    private ComplianceAmlCaseRepository cases;
    private AdminApprovalRepository approvalRows;
    private KycProviderRegistry providers;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private static final String TAG_PATH = "/api/v1/admin/compliance/users/42/risk-tags";
    private static final String CASE_PATH = "/api/v1/admin/compliance/users/42/aml-cases";

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "compliance_write_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)"); jdbc.execute("INSERT INTO gateway_users VALUES(7),(8),(42)");
        String init = Files.readString(Path.of("../init.sql"));
        for (String table : List.of("gateway_user_kyc_profiles", "gateway_user_risk_tags", "gateway_user_aml_cases",
                "gateway_kyc_provider_events", "gateway_admin_approval_requests")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        profiles = new ComplianceKycRepository(jdbc); tags = spy(new ComplianceRiskTagRepository(jdbc));
        cases = spy(new ComplianceAmlCaseRepository(jdbc)); approvalRows = new AdminApprovalRepository(jdbc);
        profiles.submit(42, new KycSubmissionRequest("INDIVIDUAL", "STANDARD", "SG", "PASSPORT", "SUMSUB", "session-42", null, "PENDING", List.of()), "[]", Instant.now());
        var auth = mock(AuthService.class);
        var writer = new JwtPrincipal(7, "writer", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600));
        var reviewer = new JwtPrincipal(8, "reviewer", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600));
        when(auth.authenticateAdminBearer("Bearer writer")).thenReturn(writer);
        when(auth.authenticateAdminBearer("Bearer reviewer")).thenReturn(reviewer);
        when(auth.requireAdminPermission("Bearer writer", "admin.compliance.write")).thenReturn(writer);
        when(auth.adminUser("Bearer writer", 42)).thenReturn(new AuthenticatedUser(42, "user", null, "NORMAL", List.of("USER"), Instant.now()));
        var properties = new GatewayProperties(); properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(true);
        providers = mock(KycProviderRegistry.class); when(providers.simulationEnabled()).thenReturn(true);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(AdminApprovalService.class, () -> new AdminApprovalService(auth, mock(AdminAuditRepository.class), approvalRows, properties));
        context.registerBean(ComplianceService.class, () -> new ComplianceService(mock(), profiles, new KycProviderEventRepository(jdbc), tags, cases,
                auth, context.getBean(AdminApprovalService.class), mock(KycDocumentService.class), mock(AdminAuditRepository.class),
                mock(AuthPersistenceService.class), mock(SecurityNotificationService.class), providers, mock(CountryRepository.class)));
        context.refresh(); approvals = context.getBean(AdminApprovalService.class); service = context.getBean(ComplianceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminComplianceController(service, json)).build();
    }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    static Stream<Arguments> providerResults() {
        return Stream.of("PENDING", "VERIFIED", "REJECTED").flatMap(status ->
                Stream.of("PENDING", "NOT_REQUIRED", "VERIFIED", "FAILED").map(face -> Arguments.of(status, face)));
    }
    @ParameterizedTest @MethodSource("providerResults")
    void providerDecisionOwnsReviewAndFaceStateWhileRepositoryOnlyWritesFacts(String status, String priorFace) {
        jdbc.update("UPDATE gateway_user_kyc_profiles SET face_verification_status=?, reviewed_by_user_id=7, reviewed_at=?, rejection_reason='before' WHERE user_id=42",
                priorFace, Timestamp.from(Instant.now().minusSeconds(1)));
        when(providers.verifyCallback(eq("SUMSUB"), any(), any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-42", "42", "event-1", status, " rejected ")));
        var updated = service.applyProviderCallback("SUMSUB", Map.of(), new byte[0]);
        String expectedFace = priorFace.equals("PENDING") && status.equals("VERIFIED") ? "VERIFIED"
                : priorFace.equals("PENDING") && status.equals("REJECTED") ? "FAILED" : priorFace;
        assertThat(updated.status()).isEqualTo(status);
        assertThat(updated.faceVerificationStatus()).isEqualTo(expectedFace);
        assertThat(updated.reviewedByUserId()).isNull();
        if (status.equals("PENDING")) assertThat(updated.reviewedAt()).isNull();
        else assertThat(updated.reviewedAt()).isNotNull();
        assertThat(updated.rejectionReason()).isEqualTo(status.equals("REJECTED") ? "rejected" : null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_kyc_provider_events", Integer.class)).isEqualTo(1);
    }

    @Test void approvedHttpWritesNormalizeInputsAndRetainAmlClosureOnReopening() throws Exception {
        String body = json.writeValueAsString(new RiskTagCreateRequest(" high_risk ", " high ", " manual ", " review "));
        JsonNode tag = read(approved(TAG_PATH, body).request());
        assertThat(tag.path("tagCode").asString()).isEqualTo("HIGH_RISK");
        assertThat(tag.path("severity").asString()).isEqualTo("HIGH");
        assertThat(tag.path("reason").asString()).isEqualTo("review");
        String createCase = json.writeValueAsString(new AmlCaseCreateRequest(" reviewing ", 75, " manual ", " review ", 8L));
        long id = read(approved(CASE_PATH, createCase).request()).path("caseId").asLong();
        String updatePath = "/api/v1/admin/compliance/aml-cases/" + id + "/status";
        var cleared = read(approved(updatePath, json.writeValueAsString(new AmlCaseStatusUpdateRequest(" cleared ", null))).request());
        assertThat(cleared.path("status").asString()).isEqualTo("CLEARED");
        assertThat(cleared.path("closedAt").isNull()).isFalse();
        Instant closed = jdbc.queryForObject("SELECT closed_at FROM gateway_user_aml_cases WHERE case_id=?", Timestamp.class, id).toInstant();
        var reopened = read(approved(updatePath, json.writeValueAsString(new AmlCaseStatusUpdateRequest(" reviewing ", null))).request());
        assertThat(reopened.path("riskScore").asInt()).isEqualTo(75);
        assertThat(jdbc.queryForObject("SELECT closed_at FROM gateway_user_aml_cases WHERE case_id=?", Timestamp.class, id).toInstant()).isEqualTo(closed);
    }

    @ParameterizedTest @ValueSource(strings = {"tag", "case"})
    void databaseFailureRollsBackApprovalConsumptionWithTheAdminWrite(String kind) throws Exception {
        boolean tag = kind.equals("tag");
        String path = tag ? TAG_PATH : CASE_PATH;
        String body = tag ? json.writeValueAsString(new RiskTagCreateRequest("HIGH_RISK", "HIGH", null, "FAIL"))
                : json.writeValueAsString(new AmlCaseCreateRequest("OPEN", 10, null, "FAIL", null));
        var write = approved(path, body);
        String table = tag ? "gateway_user_risk_tags" : "gateway_user_aml_cases";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT reject_write CHECK(" + (tag ? "reason" : "summary") + "<>'FAIL') NOT VALID");
        assertThatThrownBy(() -> mvc.perform(write.request())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(approvalStatus(write.approvalId())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"severity", "score", "country", "provider"})
    void malformedInputDoesNotConsumeApprovedRequest(String invalid) throws Exception {
        String path, body;
        if (invalid.equals("severity")) {
            path = TAG_PATH; body = json.writeValueAsString(new RiskTagCreateRequest("HIGH_RISK", "wrong", null, "review"));
        } else if (invalid.equals("score")) {
            path = CASE_PATH; body = json.writeValueAsString(new AmlCaseCreateRequest("OPEN", 101, null, "review", null));
        } else {
            path = "/api/v1/admin/compliance/users/42/kyc";
            body = json.writeValueAsString(new KycUpdateRequest("STANDARD", "VERIFIED", invalid.equals("country") ? "bad" : "SG",
                    "PASSPORT", invalid.equals("provider") ? "unknown" : "SELF", null, null, null));
        }
        var write = approved(path, body);
        mvc.perform(write.request()).andExpect(status().isBadRequest());
        assertThat(approvalStatus(write.approvalId())).isEqualTo("APPROVED");
        verifyNoInteractions(tags, cases);
        assertThat(profiles.find(42).status()).isEqualTo("PENDING");
    }

    @Test void duplicateRiskTagReportsBusinessFailureAndDoesNotConsumeSecondApproval() throws Exception {
        String body = json.writeValueAsString(new RiskTagCreateRequest("HIGH_RISK", "HIGH", null, "review"));
        jdbc.execute("CREATE UNIQUE INDEX tag_active_unique ON gateway_user_risk_tags(user_id,tag_code) WHERE status='ACTIVE'");
        read(approved(TAG_PATH, body).request());
        var duplicate = approved(TAG_PATH, body);
        mvc.perform(duplicate.request()).andExpect(status().isBadRequest());
        assertThat(approvalStatus(duplicate.approvalId())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_user_risk_tags", Integer.class)).isEqualTo(1);
    }

    private record Approved(long approvalId, MockHttpServletRequestBuilder request) {
    }
    private Approved approved(String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        long id = approvals.create("Bearer writer", new AdminApprovalCommand("gateway-admin", "POST", path, null, hash, "review")).approvalId();
        approvals.approve("Bearer reviewer", id, "reviewed");
        return new Approved(id, post(path).header("Authorization", "Bearer writer").header("X-Admin-Approval-Id", Long.toString(id))
                .contentType("application/json").content(bytes));
    }
    private String approvalStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM gateway_admin_approval_requests WHERE approval_id=?", String.class, id);
    }
    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
