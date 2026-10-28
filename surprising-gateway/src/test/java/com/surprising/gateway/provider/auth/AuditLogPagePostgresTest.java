package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.UserAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AuditLogPagePostgresTest {
    final long first=9007199254740993L;
    JdbcTemplate admin,jdbc;String schema;
    GatewayLoginLogRepository logins;AdminAuditRepository audits;
    MockMvc mvc;ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="audit_page_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password));
        jdbc.execute("CREATE TABLE gateway_users(user_id bigint PRIMARY KEY)");jdbc.execute("INSERT INTO gateway_users VALUES(7),(8),(99)");
        String init=Files.readString(Path.of("../init.sql"));
        for(String table:List.of("gateway_login_logs","gateway_admin_operation_logs")) {
            int start=init.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            jdbc.execute(init.substring(start,init.indexOf("\n);",start)+3));
        }
        Instant now=Instant.parse("2026-10-21T07:00:00.123456Z");
        jdbc.update("INSERT INTO gateway_login_logs(login_id,user_id,result,reason,user_agent,ip_address,created_at) "
                +"SELECT ?+g,7,'SUCCESS','LOGIN','client','203.0.113.7',? FROM generate_series(0,500) g",first,Timestamp.from(now));
        jdbc.update("INSERT INTO gateway_login_logs(login_id,user_id,result,reason,created_at) VALUES(?,8,'SUCCESS','LOGIN',?)",first+1000,Timestamp.from(now));
        logins=spy(new GatewayLoginLogRepository(jdbc));audits=spy(new AdminAuditRepository(jdbc));
        for(int i=0;i<3;i++)audits.recordRequired(new AdminOperationRecord(i==2?8L:7L,"admin",List.of("ADMIN"),"account","POST",
                "/api/v1/admin/accounts/adjustments","referenceId=operation-"+i,"SPOT","a".repeat(64),200,1L,true,null,"qa","127.0.0.1",now));
        jdbc.update("UPDATE gateway_admin_operation_logs SET operation_id=operation_id+?",first);
        clearInvocations(audits,logins);
        var persistence=new AuthPersistenceService(mock(GatewayUserRepository.class),mock(GatewayRoleRepository.class),
                mock(GatewayPermissionRepository.class),mock(GatewayUserRoleRepository.class),mock(GatewayRolePermissionRepository.class),
                logins,mock(GatewayUserMfaRepository.class),mock(GatewayRefreshSessionRepository.class));
        var properties=new GatewayProperties();
        var auth=spy(new AuthService(properties,persistence,mock(PasswordHasher.class),mock(JwtTokenService.class),
                mock(EmailVerificationService.class),mock(LoginVerificationService.class),mock(GatewayUserAccessBlockRepository.class),()->null, mock(com.surprising.affiliate.service.AffiliateService.class)));
        doReturn(new JwtPrincipal(7,"user","NORMAL",List.of("USER"),now.plusSeconds(3600))).when(auth).authenticateBearer("Bearer user");
        doReturn(new JwtPrincipal(99,"admin","NORMAL",List.of("ADMIN"),now.plusSeconds(3600))).when(auth).authenticateAdminBearer("Bearer admin");
        var approvals=new AdminApprovalService(auth,audits,mock(AdminApprovalRepository.class),properties);
        mvc=MockMvcBuilders.standaloneSetup(new AdminAuthController(auth,approvals,json),
                new UserSecurityController(auth,mock(UserSecurityService.class),mock(LoginVerificationService.class),
                        mock(SensitiveActionVerificationService.class),persistence,mock(UserAccessService.class),properties)).build();
    }
    @AfterEach void cleanup() {if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}

    @ParameterizedTest @ValueSource(strings={"createdAt.asc","createdAt.desc"})
    void ownHistoryKeepsStableTimestampTiesAndExactLongCursor(String sort) throws Exception {
        var firstPage=read(get("/api/v1/security/login-history").header("Authorization","Bearer user")
                .param("userId","8").param("result"," success ").param("limit","1").param("sort",sort));
        long expected=sort.endsWith("asc")?first:first+500;
        assertThat(firstPage.path("logs").get(0).path("loginId").asLong()).isEqualTo(expected);
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        var second=read(get("/api/v1/security/login-history").header("Authorization","Bearer user")
                .param("result","SUCCESS").param("limit","1").param("sort",sort)
                .param("cursor",firstPage.path("nextCursor").asString()));
        assertThat(second.path("logs").get(0).path("loginId").asLong()).isEqualTo(expected+(sort.endsWith("asc")?1:-1));
        for(JsonNode log:second.path("logs"))assertThat(log.path("userId").asLong()).isEqualTo(7L);
    }

    @Test void adminFiltersAndOperationCursorKeepTheirActorServiceAndMethod() throws Exception {
        var login=read(get("/api/v1/admin/audit/login-logs").header("Authorization","Bearer admin").param("userId","8").param("result","success"));
        assertThat(login.path("count").asInt()).isEqualTo(1);
        assertThat(login.path("logs").get(0).path("loginId").asLong()).isEqualTo(first+1000);
        var page=read(operation().param("limit","1"));
        assertThat(page.path("logs").get(0).path("operationId").asLong()).isEqualTo(first+2);
        var next=read(operation().param("limit","1").param("cursor",page.path("nextCursor").asString()));
        assertThat(next.path("logs").get(0).path("operationId").asLong()).isEqualTo(first+1);
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(next.path("logs").get(0).path("adminUserId").asLong()).isEqualTo(7L);
    }

    @Test void limitClampingIsBoundAtHttpEntrance() throws Exception {
        var minimum=read(get("/api/v1/security/login-history").header("Authorization","Bearer user").param("limit","0"));
        assertThat(minimum.path("count").asInt()).isEqualTo(1);assertThat(minimum.path("limit").asInt()).isEqualTo(1);
        var maximum=read(get("/api/v1/security/login-history").header("Authorization","Bearer user").param("limit","2147483647"));
        assertThat(maximum.path("count").asInt()).isEqualTo(500);assertThat(maximum.path("limit").asInt()).isEqualTo(500);
    }

    @ParameterizedTest @ValueSource(strings={"sort", "base64", "timestamp"})
    void malformedPageIsRejectedBeforeAuditRepository(String invalid) throws Exception {
        var request=operation();
        if(invalid.equals("sort"))request.param("sort","createdAt DESC;DROP TABLE gateway_users");
        else request.param("cursor",invalid.equals("base64")?"***":java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("invalid-time|"+first).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(request).andExpect(status().isBadRequest());verifyNoInteractions(audits);
    }

    private MockHttpServletRequestBuilder operation() {
        return get("/api/v1/admin/audit/operations").header("Authorization","Bearer admin")
                .param("adminUserId","7").param("service"," ACCOUNT ").param("method"," post ").param("success","true");
    }
    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
