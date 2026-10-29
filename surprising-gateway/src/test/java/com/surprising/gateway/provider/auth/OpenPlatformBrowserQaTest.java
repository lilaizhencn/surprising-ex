package com.surprising.gateway.provider.auth;

import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.*;
import com.surprising.gateway.provider.controller.*;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.product.*;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.provider.config.*;
import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import com.surprising.websocket.provider.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.*;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Owned loopback HTTP/WS fixture: real JWT, Key crypto/PostgreSQL and Valkey; simulated OTP, no trading Core. */
@EnabledIfEnvironmentVariable(named = "OPEN_PLATFORM_QA_DIR", matches = ".+")
class OpenPlatformBrowserQaTest {
    static final ObjectMapper json = new ObjectMapper();
    static GatewayProperties properties;
    static GatewayApiKeyService keys;
    static AuthService auth;
    static GatewayRequestLimits limits;
    static GatewayProductServices products;
    static WebSocketProperties websocket;

    @Configuration @EnableWebMvc @EnableWebSocket
    static class Web implements WebSocketConfigurer {
        @Bean UserApiKeyController keyController() { return new UserApiKeyController(keys, properties); }
        @Bean GatewayLimitErrors errors() { return new GatewayLimitErrors(); }
        @Bean Probes probes() { return new Probes(); }
        @Bean SubscriptionRegistry subscriptions() { return new SubscriptionRegistry(json, websocket); }
        @Bean ClientWebSocketHandler handler() { return new ClientWebSocketHandler(json, subscriptions(), websocket,
                new WebSocketAuthenticator(auth, keys), products, limits); }
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            new WebSocketServerConfiguration(handler(), websocket, properties).registerWebSocketHandlers(registry);
        }
    }

    @RestController static class Probes {
        @GetMapping("/api/v1/time") Map<String, Long> time() { return Map.of("serverTime", System.currentTimeMillis()); }
        @GetMapping("/api/v1/security/mfa") Map<String, Boolean> mfa(@RequestHeader("Authorization") String token) {
            auth.authenticateBearer(token); return Map.of("enabled", false);
        }
        @PostMapping("/api/v1/security/verification/challenge") Map<String, Boolean> challenge(@RequestHeader("Authorization") String token) {
            auth.authenticateBearer(token); return Map.of("simulated", true);
        }
        @GetMapping("/api/v1/gateway/account/balances") ResponseEntity<?> balance(jakarta.servlet.http.HttpServletRequest request) {
            keys.authenticate(new ApiKeyRequestBinding(properties).bindNative(request, null), "READ");
            return ResponseEntity.status(503).body(Map.of("code", "READ_VIEW_UNAVAILABLE"));
        }
    }

    @Test @Timeout(1800) void serveIsolatedKeyManagementAndWebsocketUntilDone() throws Exception {
        Path output = Path.of(System.getenv("OPEN_PLATFORM_QA_DIR")).toRealPath();
        if (!output.getFileName().toString().startsWith("open-platform.")) throw new IllegalArgumentException("owned directory required");
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("INSTRUMENT_TEST_DB_USER"), ""));
        String schema = "open_platform_qa_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, System.getenv("INSTRUMENT_TEST_DB_USER"), ""));
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("GATEWAY_LIMIT_TEST_REDIS_PORT"))));
        var tomcat = new Tomcat(); var web = new AnnotationConfigWebApplicationContext();
        Path runtime = Files.createTempDirectory(output, "tomcat-");
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        String namespace = "gateway:browser:" + UUID.randomUUID();
        try {
            String init = Files.readString(Path.of("../init.sql"));
            for (String table : List.of("gateway_users", "gateway_api_keys", "gateway_user_access_blocks")) {
                int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
                jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
            }
            jdbc.execute("ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS display_name TEXT");
            jdbc.execute("INSERT INTO gateway_users(user_id,username,password_hash,created_at,updated_at) VALUES(7,'openplatformqa','unused',now(),now())");
            factory.afterPropertiesSet(); factory.start(); var redis = new StringRedisTemplate(factory);
            properties = new GatewayProperties();
            properties.getSecurity().setJwtSecret(UUID.randomUUID() + UUID.randomUUID().toString());
            properties.getSecurity().setCorsAllowedOrigins(List.of("http://127.0.0.1:17174"));
            var policies = new GatewayLimitProperties(); policies.setNamespace(namespace);
            limits = new GatewayRequestLimits(redis, policies, new SimpleMeterRegistry());
            var binding = new GatewayLimitRequestBinding(json); var metadata = new AuthClientMetadataBinding(properties, limits, binding);
            var persistence = mock(AuthPersistenceService.class); var users = new GatewayUserRepository(jdbc);
            when(persistence.user(anyLong())).thenAnswer(call -> users.find(call.getArgument(0)).map(user -> new AuthenticatedUser(
                    user.userId(), user.username(), user.email(), user.status(), List.of("USER"), user.createdAt())));
            var jwt = new JwtTokenService(properties, json);
            auth = new AuthService(properties, persistence, mock(PasswordHasher.class), jwt,
                    mock(EmailVerificationService.class), mock(LoginVerificationService.class), new GatewayUserAccessBlockRepository(jdbc), metadata,
                    mock(com.surprising.affiliate.service.AffiliateService.class));
            var verification = mock(SensitiveActionVerificationService.class);
            when(verification.verify(anyLong(), eq("SECURITY_SETTINGS"), any(), any(), any()))
                    .thenAnswer(call -> "123456".equals(call.getArgument(2)));
            keys = new GatewayApiKeyService(new GatewayApiKeyRepository(jdbc), auth, new TotpService(properties), verification, properties, metadata);
            products = mock(GatewayProductServices.class); when(products.enabled()).thenReturn(List.of(ProductLine.values()));
            websocket = new WebSocketProperties(); websocket.getSecurity().setAllowedOrigins(List.of("http://127.0.0.1:17174"));
            var cors = new GatewayCorsConfig().gatewayCorsSource(properties, websocket);
            tomcat.setBaseDir(runtime.toString()); tomcat.setPort(19084); tomcat.getConnector().setProperty("address", "127.0.0.1");
            var context = tomcat.addContext("", runtime.toString()); context.addServletContainerInitializer(new WsSci(), Set.of());
            context.addApplicationListener("org.springframework.web.context.request.RequestContextListener");
            var rate = new org.apache.tomcat.util.descriptor.web.FilterDef(); rate.setFilterName("limits");
            rate.setFilter(new GatewayLimitFilter(limits, binding, properties, json, cors)); rate.setAsyncSupported("true"); context.addFilterDef(rate);
            var rateMap = new org.apache.tomcat.util.descriptor.web.FilterMap(); rateMap.setFilterName("limits"); rateMap.addURLPattern("/*"); context.addFilterMap(rateMap);
            var crossOrigin = new org.apache.tomcat.util.descriptor.web.FilterDef(); crossOrigin.setFilterName("cors");
            crossOrigin.setFilter(new CorsFilter(cors)); context.addFilterDef(crossOrigin);
            var corsMap = new org.apache.tomcat.util.descriptor.web.FilterMap(); corsMap.setFilterName("cors"); corsMap.addURLPattern("/*"); context.addFilterMap(corsMap);
            web.register(Web.class); var servlet = Tomcat.addServlet(context, "gateway", new DispatcherServlet(web));
            servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "gateway"); tomcat.start();
            var authorization = new WebSocketSessionAuthorization(web.getBean(SubscriptionRegistry.class), new WebSocketAuthenticator(auth, keys), Optional.empty());
            scheduler.scheduleWithFixedDelay(() -> {
                for (var connection : web.getBean(SubscriptionRegistry.class).connections())
                    if (connection.authenticatedUserId() != null) authorization.revalidate(connection);
            }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
            Path state = output.resolve("qa-runtime.json");
            Files.writeString(state, json.writeValueAsString(Map.of("port", 19084, "userId", 7,
                    "token", jwt.createAccessToken(7, "openplatformqa", List.of("USER"), Instant.now()), "schema", schema)));
            Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            long deadline = System.nanoTime() + 1740_000_000_000L;
            while (!Files.exists(output.resolve("qa-done")) && System.nanoTime() < deadline) Thread.sleep(100);
        } finally {
            scheduler.shutdownNow(); tomcat.stop(); tomcat.destroy(); web.close(); factory.destroy();
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
