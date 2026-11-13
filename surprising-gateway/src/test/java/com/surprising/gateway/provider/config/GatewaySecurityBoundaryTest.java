package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.controller.GatewayLimitRequestBinding;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.*;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Real servlet parsing + real security chain, JWT and admin controller. No live database or Core. */
class GatewaySecurityBoundaryTest {
    @TempDir Path directory;
    Tomcat server;
    AnnotationConfigWebApplicationContext context;
    HttpClient client;
    String base;
    String token;
    GatewayProperties properties;
    AuthPersistenceService rows;
    com.surprising.gateway.provider.auth.state.AuthStateFixture fixture;

    @BeforeEach void start() throws Exception {
        server = new Tomcat(); server.setBaseDir(directory.toString()); server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        var servlet = server.addContext("", directory.toString());
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(servlet.getServletContext()); context.register(Configuration.class); context.refresh();
        Tomcat.addServlet(servlet, "mvc", new DispatcherServlet(context)); servlet.addServletMappingDecoded("/", "mvc");
        install(servlet, "requestContext", new RequestContextFilter());
        install(servlet, "security", new DelegatingFilterProxy("springSecurityFilterChain", context));
        server.start(); base = "http://127.0.0.1:" + server.getConnector().getLocalPort();
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        properties = context.getBean(GatewayProperties.class); rows = context.getBean(AuthPersistenceService.class);
        when(rows.user(1)).thenReturn(Optional.of(new AuthenticatedUser(1, "reviewer", "reviewer@example.test", "NORMAL", List.of("SUPPORT"), Instant.now())));
        when(rows.user(2)).thenReturn(Optional.of(new AuthenticatedUser(2, "target", "target@example.test", "NORMAL", List.of("USER"), Instant.now())));
        fixture = context.getBean(com.surprising.gateway.provider.auth.state.AuthStateFixture.class);
        loginReviewer(List.of());
        token = context.getBean(JwtTokenService.class).createSessionToken(1, "reviewer", List.of("SUPPORT"), 1088L, Instant.now());
    }

    /** Valkey 里放入评审员用户（带给定权限）和一个有效会话。 */
    void loginReviewer(List<String> permissions) {
        fixture.state.putUser(com.surprising.gateway.provider.auth.state.AuthStateFixture.user(1, "NORMAL", List.of("SUPPORT"), permissions));
        fixture.startSession(1, 1088L);
    }

    private static void install(org.apache.catalina.Context context, String name, jakarta.servlet.Filter filter) {
        var definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); definition.setAsyncSupported("true"); context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); mapping.setDispatcher("REQUEST"); context.addFilterMap(mapping);
    }

    @AfterEach void stop() throws Exception {
        client.close(); server.stop(); server.destroy(); context.close();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void permissionAndIpGatesCannotBeBypassedWithServletPathVariants() throws Exception {
        assertThat(get("/api/v1/admin/users/2").statusCode()).isEqualTo(403);
        for (String path : List.of("/api/v1/%61dmin/users/2", "/api/v1/admin;review=1/users/2", "/api/v1/admin//users/2", "/api/v1/admin/%2e/users/2"))
            assertThat(get(path).statusCode()).as(path).isEqualTo(400);
        loginReviewer(List.of("admin.users.read"));
        assertThat(get("/api/v1/admin/users/2").statusCode()).isEqualTo(200);
        properties.getSecurity().setAdminIpAllowlist(List.of("192.0.2.1"));
        assertThat(get("/api/v1/admin/users/2").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/%61dmin/users/2").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/admin;review=1/users/2").statusCode()).isEqualTo(400);
    }

    @Test void bearerIsVerifiedOncePerRequestAndUnknownRoutesDenyByDefault() throws Exception {
        loginReviewer(List.of("admin.users.read"));
        clearInvocations(rows);
        assertThat(get("/api/v1/admin/users/2").statusCode()).isEqualTo(200);
        // 登录后的校验只读 Valkey，用户目录本身的查询只发生在管理接口读取目标用户时
        verify(rows, never()).permissionsForUser(anyLong());
        verify(rows, never()).activeRefreshSession(anyLong(), anyLong(), any());
        verify(rows, never()).roles(anyLong());
        verify(rows, times(1)).user(2);
        assertThat(get("/api/v1/new-unlisted-endpoint").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/new-unlisted-endpoint").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/realtime/SPOT/state").statusCode()).isEqualTo(404); // Known private route, absent controller in this fixture.
        var anonymousState = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/realtime/SPOT/state")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymousState.statusCode()).isEqualTo(401);
        var anonymous = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/security/sessions")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(anonymous.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(anonymous.headers().firstValue("Location")).isEmpty();
    }

    @Test void browserLoginSetsHttpOnlyCookieAndCookieWritesNeedCsrfWhileBearerIsExempt() throws Exception {
        var auth = context.getBean(AuthService.class);
        var session = new AuthResponse(rows.user(1).orElseThrow(), token, Instant.now().plusSeconds(3600));
        doReturn(session).when(auth).login(any(), any());
        var csrfResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).header("X-Auth-Client", "web").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        assertThat(csrfResponse.headers().allValues("Set-Cookie")).anySatisfy(value -> assertThat(value)
                .startsWith("surprising-web-csrf=").contains("SameSite=Lax").doesNotContain("HttpOnly"));
        String csrf = new ObjectMapper().readTree(csrfResponse.body()).path("token").asString();
        String json = "{\"identifier\":\"reviewer@example.test\",\"password\":\"StrongPassword1!\"}";
        java.util.function.Function<String, HttpRequest> login = token -> {
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login")).header("X-Auth-Client", "web")
                    .header("X-Auth-Transport", "cookie").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (token != null) request.header("X-CSRF-Token", token);
            return request.build();
        };
        assertThat(client.send(login.apply(null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var response = client.send(login.apply(csrf), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(token).contains("\"browserSession\":true");
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(value -> assertThat(value)
                .startsWith("surprising-web-session=" + token).contains("HttpOnly", "SameSite=Lax", "Path=/"));
        // 之后只靠 Cookie 就能通过登录校验，不需要 Authorization 头
        var me = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me")).header("X-Auth-Client", "web").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        // Cookie 认证的写请求没有 CSRF 头会被拒绝
        var noCsrf = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout")).header("X-Auth-Client", "web").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        // 跨站来源的后台请求被拒绝
        // Authorization 头认证的写请求（App/脚本）不需要 CSRF
        var bearer = java.net.http.HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout"))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bearer.statusCode()).isEqualTo(204);
        assertThat(fixture.state.sessionExists(1088L)).isFalse();
        // 登出后同一个令牌不再有效
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me")).header("X-Auth-Client", "web").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test void adminCookiesCannotBeBorrowedByTheUserWebsiteAndCsrfCookiesRemainSeparate() throws Exception {
        String adminOrigin = base;
        properties.getSecurity().setAdminBrowserAllowedOrigins(List.of(adminOrigin));
        var userCsrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .header("X-Auth-Client", "web").GET().build(), HttpResponse.BodyHandlers.ofString());
        var adminCsrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .header("X-Auth-Client", "admin").header("Origin", adminOrigin).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(userCsrf.headers().allValues("Set-Cookie")).anySatisfy(value -> assertThat(value).startsWith("surprising-web-csrf="));
        assertThat(adminCsrf.headers().allValues("Set-Cookie")).anySatisfy(value -> assertThat(value).startsWith("surprising-admin-csrf="));
        String csrf = new ObjectMapper().readTree(adminCsrf.body()).path("token").asString();
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout"))
                .header("X-Auth-Client", "admin").header("X-CSRF-Token", csrf).header("Origin", "https://ex.tokdou.com")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var rejected = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(403); assertThat(rejected.body()).contains("ADMIN_ORIGIN_DENIED");
        var noOrigin = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/logout"))
                .header("X-Auth-Client", "admin").header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(noOrigin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({GatewaySecurityConfiguration.class, GatewayCorsConfig.class, AdminIpWhitelistFilter.class, AdminPermissionFilter.class,
            AdminAuthController.class, AuthController.class, AuthClientMetadataBinding.class, BrowserSessionController.class, BrowserSessionResponse.class})
    static class Configuration {
        @Bean GatewayProperties properties() { var p = new GatewayProperties(); p.getSecurity().setBrowserCookieSecure(false); return p; }
        @Bean WebSocketProperties websocket() { return new WebSocketProperties(); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean GatewayRequestLimits limits() { return mock(GatewayRequestLimits.class); }
        @Bean GatewayLimitRequestBinding requests(ObjectMapper json) { return new GatewayLimitRequestBinding(json); }
        @Bean AuthPersistenceService rows() { return mock(AuthPersistenceService.class); }
        @Bean JwtTokenService tokens(GatewayProperties p, ObjectMapper json) { return new JwtTokenService(p, json); }
        @Bean AdminApprovalService approvals() { return mock(AdminApprovalService.class); }
        @Bean PasswordResetService passwordResets() { return mock(PasswordResetService.class); }
        @Bean com.surprising.gateway.provider.auth.state.AuthStateFixture authFixture(GatewayProperties p) { return new com.surprising.gateway.provider.auth.state.AuthStateFixture(p); }
        @Bean AuthService auth(GatewayProperties p, com.surprising.gateway.provider.auth.state.AuthStateFixture fixture, AuthPersistenceService rows, JwtTokenService jwt, AuthClientMetadataBinding client) {
            return spy(new AuthService(p, rows, mock(PasswordHasher.class), jwt, mock(EmailVerificationService.class),
                    mock(LoginVerificationService.class), mock(GatewayUserAccessBlockRepository.class), client, mock(com.surprising.affiliate.service.AffiliateService.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.legal.service.LegalConsentService.class), fixture.state, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.state.AuthStateSync.class)));
        }
    }
}
