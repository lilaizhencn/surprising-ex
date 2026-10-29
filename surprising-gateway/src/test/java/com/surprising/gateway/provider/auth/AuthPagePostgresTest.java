package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.client.KycProviderRegistry;
import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP binding, service aggregation and six PostgreSQL repositories. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class AuthPagePostgresTest {
    private static final long FIRST = 9007199254740993L;
    private static final Instant TIME = Instant.parse("2026-10-22T07:00:00.123456Z");
    private JdbcTemplate admin, jdbc;
    private String schema;
    private GatewayUserRepository users;
    private GatewayRefreshSessionRepository sessions;
    private AdminApprovalRepository approvals;
    private ComplianceRiskTagRepository tags;
    private ComplianceAmlCaseRepository cases;
    private ComplianceUserProjectionRepository complianceUsers;
    private GatewayUserRoleRepository userRoles;
    private GatewayRoleRepository roles;
    private AuthService auth;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "auth_page_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        String init = Files.readString(Path.of("../init.sql"));
        for (String table : List.of("gateway_users", "gateway_roles", "gateway_user_roles",
                "gateway_refresh_sessions", "gateway_user_kyc_profiles", "gateway_user_risk_tags",
                "gateway_user_aml_cases", "gateway_admin_approval_requests")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        jdbc.execute("ALTER TABLE gateway_users ADD COLUMN display_name TEXT");
        jdbc.update("INSERT INTO gateway_users(user_id,username,password_hash,created_at,updated_at) "
                + "VALUES(7,'owner','hash',?,?),(8,'other','hash',?,?),(99,'admin','hash',?,?)",
                Timestamp.from(TIME.minusSeconds(1)), Timestamp.from(TIME.minusSeconds(1)),
                Timestamp.from(TIME.minusSeconds(1)), Timestamp.from(TIME.minusSeconds(1)),
                Timestamp.from(TIME.minusSeconds(1)), Timestamp.from(TIME.minusSeconds(1)));
        jdbc.execute("INSERT INTO gateway_roles(role_id,role_code,role_name) VALUES(1,'USER','User'),(2,'SUPPORT','Support')");
        for (int i = 0; i < 2; i++) {
            long id = FIRST + i;
            jdbc.update("INSERT INTO gateway_users(user_id,username,email,password_hash,created_at,updated_at) VALUES(?,?,?,'hash',?,?)",
                    id, "page_user_" + i, i == 0 ? "literal%_@example.test" : "literalab@example.test",
                    Timestamp.from(TIME), Timestamp.from(TIME));
            jdbc.update("INSERT INTO gateway_user_roles(user_id,role_id) VALUES(?,1),(?,2)", id, id);
            jdbc.update("INSERT INTO gateway_refresh_sessions(session_id,user_id,token_hash,expires_at,created_at,updated_at) VALUES(?,7,?,?,?,?)",
                    id, "hash_" + i, Timestamp.from(TIME.plusSeconds(315360000)), Timestamp.from(TIME), Timestamp.from(TIME));
            jdbc.update("INSERT INTO gateway_user_kyc_profiles(user_id,kyc_level,status,updated_at) VALUES(?,'STANDARD','VERIFIED',?)", id, Timestamp.from(TIME));
            jdbc.update("INSERT INTO gateway_user_risk_tags(tag_id,user_id,tag_code,severity,reason,created_at,updated_at) VALUES(?,?,'HIGH_RISK','HIGH','review',?,?)",
                    id, id, Timestamp.from(TIME), Timestamp.from(TIME));
            jdbc.update("INSERT INTO gateway_user_aml_cases(case_id,user_id,status,summary,created_at,updated_at) VALUES(?,?,'REVIEWING','review',?,?)",
                    id, id, Timestamp.from(TIME), Timestamp.from(TIME));
            jdbc.update("INSERT INTO gateway_admin_approval_requests(approval_id,requester_user_id,service,http_method,request_path,reason,requested_at,expires_at) "
                    + "VALUES(?,7,'account','POST','/api/v1/admin/accounts/adjustments','review',?,?)",
                    id, Timestamp.from(TIME), Timestamp.from(TIME.plusSeconds(3600)));
        }
        jdbc.update("INSERT INTO gateway_refresh_sessions(session_id,user_id,token_hash,expires_at,created_at) VALUES(?,8,'other',?,?)",
                FIRST + 1000, Timestamp.from(TIME.plusSeconds(315360000)), Timestamp.from(TIME));
        users = spy(new GatewayUserRepository(jdbc)); sessions = spy(new GatewayRefreshSessionRepository(jdbc));
        approvals = spy(new AdminApprovalRepository(jdbc)); tags = spy(new ComplianceRiskTagRepository(jdbc));
        cases = spy(new ComplianceAmlCaseRepository(jdbc)); complianceUsers = spy(new ComplianceUserProjectionRepository(jdbc));
        userRoles = spy(new GatewayUserRoleRepository(jdbc)); roles = spy(new GatewayRoleRepository(jdbc));
        var persistence = new AuthPersistenceService(users, roles, mock(GatewayPermissionRepository.class), userRoles,
                mock(GatewayRolePermissionRepository.class), mock(GatewayLoginLogRepository.class),
                mock(GatewayUserMfaRepository.class), sessions);
        var properties = new GatewayProperties();
        auth = spy(new AuthService(properties, persistence, mock(PasswordHasher.class), mock(JwtTokenService.class),
                mock(EmailVerificationService.class), mock(LoginVerificationService.class), mock(GatewayUserAccessBlockRepository.class), mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class), mock(com.surprising.affiliate.service.AffiliateService.class)));
        doReturn(new JwtPrincipal(7, "owner", "NORMAL", List.of("USER"), TIME.plusSeconds(3600)))
                .when(auth).authenticateBearer("Bearer user");
        doReturn(new JwtPrincipal(99, "admin", "NORMAL", List.of("SUPER_ADMIN"), TIME.plusSeconds(3600)))
                .when(auth).authenticateAdminBearer("Bearer admin");
        var audit = mock(AdminAuditRepository.class);
        var approvalService = new AdminApprovalService(auth, audit, approvals, properties);
        var compliance = new ComplianceService(complianceUsers, mock(ComplianceKycRepository.class),
                mock(KycProviderEventRepository.class), tags, cases, auth, approvalService, mock(KycDocumentService.class),
                audit, persistence, mock(SecurityNotificationService.class), mock(KycProviderRegistry.class), mock(CountryRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuthController(auth, approvalService, json),
                new AdminComplianceController(compliance, json), new UserSecurityController(auth,
                        mock(UserSecurityService.class), mock(LoginVerificationService.class), mock(SensitiveActionVerificationService.class),
                        persistence, mock(UserAccessService.class), properties)).build();
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    static Stream<Arguments> pageOrders() {
        return Stream.of(new String[]{"users", "users", "userId", "createdAt"},
                new String[]{"sessions", "sessions", "sessionId", "createdAt"},
                new String[]{"approvals", "approvals", "approvalId", "requestedAt"},
                new String[]{"compliance/users", "users", "userId", "updatedAt"},
                new String[]{"compliance/risk-tags", "tags", "tagId", "createdAt"},
                new String[]{"compliance/risk-tags", "tags", "tagId", "updatedAt"},
                new String[]{"compliance/aml-cases", "cases", "caseId", "createdAt"},
                new String[]{"compliance/aml-cases", "cases", "caseId", "updatedAt"})
                .flatMap(row -> Stream.of("asc", "desc").map(direction ->
                        Arguments.of(row[0], row[1], row[2], row[3] + "." + direction)));
    }

    @ParameterizedTest @MethodSource("pageOrders")
    void nativePagesPreserveMicrosecondsLongIdsAndFilters(String endpoint, String collection, String idField, String sort) throws Exception {
        long expected = sort.endsWith("asc") ? FIRST : FIRST + 1;
        JsonNode first = read(request(endpoint).param("limit", "1").param("sort", sort));
        assertThat(first.path(collection).get(0).path(idField).asLong()).isEqualTo(expected);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        JsonNode second = read(request(endpoint).param("limit", "1").param("sort", sort)
                .param("cursor", first.path("nextCursor").asString()));
        assertThat(second.path(collection).get(0).path(idField).asLong()).isEqualTo(sort.endsWith("asc") ? FIRST + 1 : FIRST);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        if (endpoint.equals("compliance/users")) {
            assertThat(first.path("users").get(0).path("activeRiskTags").asInt()).isEqualTo(1);
            assertThat(first.path("users").get(0).path("openAmlCases").asInt()).isEqualTo(1);
        }
        if (endpoint.equals("users")) {
            assertThat(first.path("users").get(0).path("roles").toString()).contains("USER", "SUPPORT");
            verify(userRoles, times(2)).findRoleIdsByUserIds(anyCollection());
            verify(roles, times(2)).findByIds(anyCollection());
            verify(userRoles, never()).findRoleIds(anyLong());
        }
    }

    @Test void ownSessionsIgnoreForgedUserAndKeepIdentityScope() throws Exception {
        JsonNode page = read(get("/api/v1/security/sessions").header("Authorization", "Bearer user").param("userId", "8"));
        assertThat(page.path("count").asInt()).isEqualTo(2);
        for (JsonNode session : page.path("sessions")) assertThat(session.path("userId").asLong()).isEqualTo(7);
    }

    @Test void searchRetainsExactNumericTextAndEscapesSqlWildcards() throws Exception {
        assertThat(read(adminUsers().param("query", Long.toString(FIRST))).path("count").asInt()).isEqualTo(1);
        assertThat(read(adminUsers().param("query", "0" + FIRST)).path("count").asInt()).isZero();
        assertThat(read(adminUsers().param("query", "%_")).path("users").get(0).path("userId").asLong()).isEqualTo(FIRST);
        assertThat(read(adminUsers().param("query", "%_")).path("count").asInt()).isEqualTo(1);
    }

    @Test void httpLimitsAreClampedBeforeRepository() throws Exception {
        assertThat(read(request("users").param("limit", "0")).path("limit").asInt()).isEqualTo(1);
        assertThat(read(request("users").param("limit", "2147483647")).path("limit").asInt()).isEqualTo(500);
    }

    @ParameterizedTest @ValueSource(strings = {"sort", "base64", "timestamp", "status", "service", "user", "tag"})
    void invalidHttpInputsNeverReachRepository(String invalid) throws Exception {
        MockHttpServletRequestBuilder request = request("approvals");
        switch (invalid) {
            case "sort" -> request.param("sort", "requestedAt desc;DROP TABLE gateway_users");
            case "base64" -> request.param("cursor", "***");
            case "timestamp" -> request.param("cursor", java.util.Base64.getUrlEncoder().encodeToString("bad-time|1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            case "status" -> request = adminUsers().param("status", "bogus");
            case "service" -> request = get("/api/v1/admin/approvals").header("Authorization", "Bearer admin").param("service", "account/../admin");
            case "user" -> request = get("/api/v1/admin/sessions").header("Authorization", "Bearer admin").param("userId", "0");
            case "tag" -> request = get("/api/v1/admin/compliance/users").header("Authorization", "Bearer admin").param("tagCode", "bad tag!");
        }
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(users, sessions, approvals, tags, cases, complianceUsers);
    }

    @Test void deniedCompliancePermissionDoesNotReadProjection() throws Exception {
        doReturn(new JwtPrincipal(99, "admin", "NORMAL", List.of("ADMIN"), TIME.plusSeconds(3600)))
                .when(auth).authenticateAdminBearer("Bearer admin");
        mvc.perform(request("compliance/users")).andExpect(status().isForbidden());
        verifyNoInteractions(complianceUsers);
    }

    private MockHttpServletRequestBuilder adminUsers() {
        return get("/api/v1/admin/users").header("Authorization", "Bearer admin");
    }

    private MockHttpServletRequestBuilder request(String endpoint) {
        var request = get("/api/v1/admin/" + endpoint).header("Authorization", "Bearer admin");
        switch (endpoint) {
            case "users" -> request.param("query", " PAGE_USER ").param("status", " normal ");
            case "sessions" -> request.param("userId", "7").param("active", "true");
            case "approvals" -> request.param("requesterUserId", "7").param("service", " ACCOUNT ").param("status", " pending ");
            case "compliance/users" -> request.param("kycStatus", " verified ").param("tagCode", " high_risk ");
            case "compliance/risk-tags" -> request.param("status", " active ");
            case "compliance/aml-cases" -> request.param("status", " reviewing ");
        }
        return request;
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
