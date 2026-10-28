package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual HTTP -> transactional service -> one-table repositories, using the goal-owned PostgreSQL. */
@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AuthInputBoundaryPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    GatewayProperties properties;
    AuthPersistenceService users;
    GatewayUserRepository userRows;
    PasswordHasher passwords;
    AdminApprovalService approvals;
    MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    long adminId;

    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);jdbc.execute("TRUNCATE gateway_users CASCADE");
        properties=new GatewayProperties();properties.getSecurity().setRequireEmailVerification(false);
        userRows=new GatewayUserRepository(jdbc);passwords=new PasswordHasher();
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(AuthPersistenceService.class,()->new AuthPersistenceService(userRows,new GatewayRoleRepository(jdbc),new GatewayPermissionRepository(jdbc),new GatewayUserRoleRepository(jdbc),new GatewayRolePermissionRepository(jdbc),new GatewayLoginLogRepository(jdbc),new GatewayUserMfaRepository(jdbc),new GatewayRefreshSessionRepository(jdbc)));
        context.refresh();users=context.getBean(AuthPersistenceService.class);
        adminId=users.createUser("mvc_admin","admin@input.test","unused",Instant.now()).userId();users.replaceRoles(adminId,List.of("ADMIN"),Instant.now());
        var tokens=mock(JwtTokenService.class);when(tokens.verifyAccessToken("admin-token")).thenReturn(new JwtPrincipal(adminId,"mvc_admin","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(3600)));
        context.registerBean(AuthService.class,()->new AuthService(properties,users,passwords,tokens,mock(EmailVerificationService.class),mock(LoginVerificationService.class),new GatewayUserAccessBlockRepository(jdbc),new AuthClientMetadataBinding(properties), new com.surprising.affiliate.service.AffiliateService(new com.surprising.affiliate.repository.AffiliateRepository(new com.surprising.affiliate.config.AffiliateDatabase(jdbc,new com.surprising.affiliate.config.AffiliateProperties())),new com.surprising.affiliate.config.AffiliateProperties())));
        var auth=context.getBean(AuthService.class);
        approvals=mock(AdminApprovalService.class);when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        mvc=MockMvcBuilders.standaloneSetup(new AuthController(auth,mock(PasswordResetService.class),new AuthClientMetadataBinding(properties)),new AdminAuthController(auth,approvals,json)).build();
        // Permission checks still use real persisted grants.
        jdbc.update("INSERT INTO gateway_role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM gateway_roles r CROSS JOIN gateway_permissions p WHERE r.role_code='ADMIN' AND p.permission_code IN ('admin.permissions.read','admin.permissions.write') ON CONFLICT DO NOTHING");
    }
    @AfterEach void close() {if(context!=null)context.close();}
    byte[] body(Object value) {return json.writeValueAsBytes(value);}

    @Test void registerAndLoginPreservePasswordBytesAndCanonicalEmail() throws Exception {
        String secret=" Password1! ";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("email"," Mixed@Input.Test ","password",secret)))).andExpect(status().isOk());
        var credential=userRows.findCredentialByEmail("mixed@input.test").orElseThrow();
        assertThat(credential.email()).isEqualTo("mixed@input.test");
        assertThat(passwords.matches(secret,credential.passwordHash())).isTrue();assertThat(passwords.matches(secret.trim(),credential.passwordHash())).isFalse();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("identifier"," MIXED@INPUT.TEST ","password",secret)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("identifier","mixed@input.test","password",secret.trim())))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE user_id=?",Integer.class,credential.userId())).isEqualTo(2);
    }
    @Test void phoneFormattingDoesNotBypassRegistrationSwitch() throws Exception {
        byte[] input=body(Map.of("phone","+86 138-0013-8000","password","Password1!"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
        assertThat(userRows.findCredentialByPhone("+8613800138000")).isEmpty();
        properties.getSecurity().setPhoneRegistrationEnabled(true);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk());
        assertThat(userRows.findCredentialByPhone("+8613800138000")).isPresent();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("identifier","+86 138-0013-8000","password","Password1!")))).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"bad/name","+0012345678","+1234","x"})
    void malformedIdentifierNeverWritesLoginLogs(String identifier) throws Exception {
        int logs=jdbc.queryForObject("SELECT count(*) FROM gateway_login_logs",Integer.class);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("identifier",identifier,"password","Password1!")))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_login_logs",Integer.class)).isEqualTo(logs);
    }
    @Test void lowercaseAndDuplicateRolesReachOwnAdminRuleCanonicallyWithOriginalApprovalBody() throws Exception {
        byte[] input=body(Map.of("roles",List.of(" admin ","ADMIN")));
        mvc.perform(post("/api/v1/admin/users/"+adminId+"/roles").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk());
        assertThat(users.roles(adminId)).containsExactly("ADMIN");
        var raw=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(approvals).consumeLocalApproval(eq("Bearer admin-token"),eq("gateway-admin"),any(),raw.capture());assertThat(raw.getValue()).containsExactly(input);
    }
    @Test void cannotRemoveOwnAdminRole() throws Exception {
        mvc.perform(post("/api/v1/admin/users/"+adminId+"/roles").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("roles",List.of(" user "))))).andExpect(status().isBadRequest());
        assertThat(users.roles(adminId)).containsExactly("ADMIN");
    }
    @ParameterizedTest @ValueSource(strings={"bad/role","!", " ", "x"})
    void malformedRoleNeverConsumesApprovalOrWritesAssociation(String role) throws Exception {
        mvc.perform(post("/api/v1/admin/users/"+adminId+"/roles").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("roles",List.of(role))))).andExpect(status().isBadRequest());
        verifyNoInteractions(approvals);assertThat(users.roles(adminId)).containsExactly("ADMIN");
    }
    @Test void permissionNormalizationIsAtEntryAndUnknownPermissionKeepsExistingLinks() throws Exception {
        new GatewayRoleRepository(jdbc).ensure("MVC_INPUT","Input",Instant.now());
        byte[] input=body(Map.of("permissions",List.of(" ADMIN.USERS.READ ","admin.users.read")));
        mvc.perform(post("/api/v1/admin/roles/mvc_input/permissions").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk());
        assertThat(users.rolePermissions("MVC_INPUT")).containsExactly("admin.users.read");
        mvc.perform(post("/api/v1/admin/roles/mvc_input/permissions").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("permissions",List.of("admin.users.read","admin.unknown"))))).andExpect(status().isBadRequest());
        assertThat(users.rolePermissions("MVC_INPUT")).containsExactly("admin.users.read");
        mvc.perform(get("/api/v1/admin/roles/mvc_input/permissions").header("Authorization","Bearer admin-token")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"BAD/PERMISSION","!", " ", "a"})
    void malformedPermissionNeverConsumesApproval(String permission) throws Exception {
        mvc.perform(post("/api/v1/admin/roles/admin/permissions").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("permissions",List.of(permission))))).andExpect(status().isBadRequest());verifyNoInteractions(approvals);
    }
    @Test void superAdminPolicyStillRejectsCanonicalLowercaseRole() throws Exception {
        mvc.perform(post("/api/v1/admin/roles/super_admin/permissions").header("Authorization","Bearer admin-token").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("permissions",List.of())))).andExpect(status().isBadRequest());
    }
}
