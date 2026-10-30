package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.auth.controller.AuthClientMetadataBinding;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.*;
import com.surprising.gateway.provider.config.GatewayLimitProperties.*;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.gateway.provider.product.*;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.product.api.ProductLine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.RequestContextFilter;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "GATEWAY_LIMIT_TEST_REDIS_PORT", matches = "[0-9]+")
class GatewayLimitHttpTest {
    final String key = "sx_" + "a".repeat(24), otherKey = "sx_" + "b".repeat(24);
    final ObjectMapper json = new ObjectMapper();
    final GatewayProperties gateway = new GatewayProperties();
    final GatewayLimitProperties policies = new GatewayLimitProperties();
    final GatewayApiKeyRepository keyRows = mock(GatewayApiKeyRepository.class);
    final AuthPersistenceService users = mock(AuthPersistenceService.class);
    final JwtTokenService tokens = mock(JwtTokenService.class);
    final GatewayProductServices products = mock(GatewayProductServices.class);
    final LocalBusinessApi business = mock(LocalBusinessApi.class);
    final TotpService totp = new TotpService(gateway);
    final AtomicInteger submissions = new AtomicInteger();
    final CompletableFuture<String> pending = new CompletableFuture<>();
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    AuthService auth;
    org.springframework.context.annotation.AnnotationConfigApplicationContext corsContext;
    GatewayApiKeyService keys;
    boolean repeatAuthentication;

    @BeforeEach void setup() {
        policies.setNamespace("gateway:http:" + UUID.randomUUID());
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("GATEWAY_LIMIT_TEST_REDIS_PORT"))));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        redis.getConnectionFactory().getConnection().ping();
        gateway.getRoutes().put("account", new GatewayProperties.BackendRoute("local:", "/api/v1/accounts", true));
        gateway.getRoutes().put("trading", new GatewayProperties.BackendRoute("local:", "/api/v1/trading/orders", true));
        when(products.enabled()).thenReturn(List.of(ProductLine.values()));
        when(products.local(any())).thenReturn(business);
        when(users.user(42)).thenReturn(Optional.of(new AuthenticatedUser(42, "owner", null, "NORMAL", List.of("USER"), Instant.now())));
        when(tokens.verifyAccessToken("owner")).thenReturn(new JwtPrincipal(42, "owner", "NORMAL", List.of("USER"), Instant.now().plusSeconds(3600)));
        stored(key, "READ,TRADE"); stored(otherKey, "READ");
        when(business.invoke(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(call.getArgument(3, HttpHeaders.class).getFirst("X-User-Id")).isEqualTo("42");
            if (repeatAuthentication) auth.authenticateBearer("Bearer owner");
            submissions.incrementAndGet(); return ResponseEntity.ok("{}".getBytes(StandardCharsets.UTF_8));
        });
    }

    @AfterEach void cleanup() {
        pending.complete("done");
        if (redis != null) { var names = redis.keys(policies.getNamespace() + "*"); if (names != null && !names.isEmpty()) redis.delete(names); }
        if (factory != null) factory.destroy();
        if (corsContext != null) corsContext.close();
    }

    MockMvc mvc() {
        corsContext = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        corsContext.registerBean(GatewayProperties.class, () -> gateway);
        corsContext.registerBean(com.surprising.websocket.provider.config.WebSocketProperties.class,
                com.surprising.websocket.provider.config.WebSocketProperties::new);
        corsContext.register(GatewayCorsConfig.class); corsContext.refresh();
        var cors = corsContext.getBean("gatewayCorsSource", org.springframework.web.cors.CorsConfigurationSource.class);
        var limits = new GatewayRequestLimits(redis, policies, new SimpleMeterRegistry());
        var binding = new GatewayLimitRequestBinding(json);
        var clients = new AuthClientMetadataBinding(gateway, limits, binding);
        auth = new AuthService(gateway, users, mock(PasswordHasher.class), tokens, mock(EmailVerificationService.class),
                mock(LoginVerificationService.class), mock(GatewayUserAccessBlockRepository.class), clients,
                mock(com.surprising.affiliate.service.AffiliateService.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.legal.service.LegalConsentService.class));
        keys = new GatewayApiKeyService(keyRows, auth, totp, mock(SensitiveActionVerificationService.class), gateway, clients);
        var proxy = new GatewayProxyController(gateway, new RestTemplate(), auth, keys, null, json,
                mock(com.surprising.gateway.provider.service.ProductTransferCoordinator.class), null,
                products, business, new GatewayProductSelection(products, json));
        var compatibility = new BinanceApiController(gateway, proxy, keys, null, auth, null, null, null, json);
        return MockMvcBuilders.standaloneSetup(proxy, compatibility, new AsyncReads(auth, pending))
                .setControllerAdvice(new GatewayLimitErrors())
                .addFilters(new RequestContextFilter(), new GatewayLimitFilter(limits, binding, gateway, json, cors),
                        new org.springframework.web.filter.CorsFilter(cors), new ProductRequestBodyFilter())
                .build();
    }

    @Test void nativeCompatibleAndBearerClientsShareUserQuotaDespiteDifferentKeysOrUserHeaders() throws Exception {
        policies.getUser().put(Group.READ, new Rule(1, 2)); var mvc = mvc();
        mvc.perform(nativeRequest("GET", "/api/v1/gateway/account/balances", null, key).header("X-User-Id", "999"))
                .andExpect(status().isOk());
        String query = "timestamp=" + System.currentTimeMillis();
        mvc.perform(get("/api/v3/account/status?" + query + "&signature=" + keys.sign("secret", query))
                        .header("X-MBX-APIKEY", otherKey).with(r -> { r.setRemoteAddr("192.0.2.7"); return r; }))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner")
                        .with(r -> { r.setRemoteAddr("198.51.100.7"); return r; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(submissions).hasValue(1);
        assertThat(redis.keys(policies.getNamespace() + ":{user:999}:*")).isEmpty();
    }

    @Test void repeatedAuthenticationInOneRequestIsChargedOnce() throws Exception {
        policies.getUser().put(Group.READ, new Rule(1, 1)); repeatAuthentication = true; var mvc = mvc();
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner"))
                .andExpect(status().isTooManyRequests());
        assertThat(submissions).hasValue(1);
    }

    @Test void batchWeightIsChargedBeforeTheBusinessAndAnOverLimitBatchNeverSubmits() throws Exception {
        policies.getUser().put(Group.ORDER, new Rule(1, 3)); var mvc = mvc();
        String three = "{\"orders\":[{},{},{}]}";
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/trading/batch", three, key)).andExpect(status().isOk());
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/trading/batch", three, key)).andExpect(status().isTooManyRequests());
        assertThat(submissions).hasValue(1);
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/trading/cancel", "{}", key)).andExpect(status().isOk());
        assertThat(submissions).hasValue(2);
    }

    @Test void methodPathQueryAndExactBodyAreAllBoundToTheNativeSignature() throws Exception {
        var mvc = mvc(); String path = "/api/v1/gateway/trading/batch";
        var original = nativeRequest("POST", path, "{\"orders\":[{}]}", key);
        original.content("{\"orders\":[{},{}]}");
        mvc.perform(original).andExpect(status().isUnauthorized());
        mvc.perform(nativeRequest("GET", "/api/v1/gateway/account/balances", null, key)
                        .with(r -> { r.setRequestURI("/api/v1/gateway/account/positions"); return r; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(nativeRequest("POST", path, "{}", key).with(r -> { r.setMethod("DELETE"); return r; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(nativeRequest("GET", "/api/v1/gateway/account/balances", null, key).with(r -> {
            r.setQueryString(r.getQueryString().replace("SPOT", "OPTION")); r.setParameter("productLine", "OPTION"); return r;
        })).andExpect(status().isUnauthorized());
        assertThat(submissions).hasValue(0);
    }

    @Test void readOnlyKeysAndAdministrativeWritesCannotReachTheBusiness() throws Exception {
        var mvc = mvc();
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/trading", "{}", otherKey)).andExpect(status().isForbidden());
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/account/admin/balance-adjustments", "{}", key))
                .andExpect(status().isForbidden());
        mvc.perform(nativeRequest("GET", "/api/v1/gateway/account/balances", null, key).header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        assertThat(submissions).hasValue(0);
    }

    @Test void everyProductUsesTheNativeKeyWithoutConvertingItsIdentity() throws Exception {
        var mvc = mvc();
        for (var product : ProductLine.values()) mvc.perform(nativeRequest("GET", "/api/v1/gateway/account/balances", null, key, product))
                .andExpect(status().isOk());
        assertThat(submissions).hasValue(6);
    }

    @Test void keyCannotTransferToAnotherUserButCanChangeItsOwnTradingSettings() throws Exception {
        var mvc = mvc();
        mvc.perform(nativeRequest("POST", "/api/v1/gateway/account/transfers",
                        "{\"sourceAccountType\":\"SPOT\",\"targetAccountType\":\"USDT_PERPETUAL\",\"asset\":\"USDT\",\"amountUnits\":1,\"referenceId\":\"owned-reference\",\"reason\":\"test\",\"recipientUserId\":99}", key))
                .andExpect(status().isForbidden());
        assertThat(submissions).hasValue(0);
        for (String path : List.of("position-mode", "position-margin-adjustments"))
            mvc.perform(nativeRequest("POST", "/api/v1/gateway/account/" + path, "{}", key)).andExpect(status().isOk());
        assertThat(submissions).hasValue(2);
    }

    @Test void asyncRequestsKeepTheirPermitUntilCompletion() throws Exception {
        policies.setConcurrentRequests(1); var mvc = mvc();
        var result = mvc.perform(get("/api/v1/test/async").header("Authorization", "Bearer owner"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GATEWAY_BUSY"));
        var context = result.getRequest().getAsyncContext(); pending.complete("done");
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()); context.complete();
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk());
    }

    @Test void preflightAndEarlyIpRejectionsKeepCorsAndRetryAfterVisible() throws Exception {
        policies.setIp(new Rule(1, 1)); var mvc = mvc();
        mvc.perform(options("/api/v1/runtime").header("Origin", "https://ex.tokdou.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://ex.tokdou.com"));
        mvc.perform(get("/api/v1/runtime").header("Origin", "https://ex.tokdou.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ex.tokdou.com"))
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")))
                .andExpect(header().exists("Retry-After"));
        verify(tokens, never()).verifyAccessToken(any());
    }

    @Test void unavailableQuotaStoreRejectsBeforeAuthenticationOrBusinessSubmission() throws Exception {
        redis = spy(redis);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("quota store unavailable"))
                .when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
        var mvc = mvc();
        mvc.perform(get("/api/v1/gateway/account/balances?productLine=SPOT").header("Authorization", "Bearer owner"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_STORAGE_UNAVAILABLE"));
        verify(tokens, never()).verifyAccessToken(any());
        assertThat(submissions).hasValue(0);
    }

    @Test void unsupportedCompatibilityMethodsNeverReachFundsOrTrading() throws Exception {
        var mvc = mvc();
        for (String path : List.of("/sapi/v1/capital/withdraw/apply", "/sapi/v1/asset/transfer"))
            mvc.perform(get(path).header("X-MBX-APIKEY", key)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v3/account").header("X-MBX-APIKEY", key)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v3/not-supported")).andExpect(status().isNotFound());
        assertThat(submissions).hasValue(0);
    }

    private void stored(String key, String permissions) {
        when(keyRows.active(key)).thenReturn(Optional.of(new GatewayApiKeyRepository.ApiKeyRecord(UUID.randomUUID(), 42,
                key, totp.encryptSecret("secret"), "bot", permissions, "192.0.2.7", "ACTIVE", Instant.now(), null, null)));
    }

    private MockHttpServletRequestBuilder nativeRequest(String method, String path, String body, String key) throws Exception {
        return nativeRequest(method, path, body, key, ProductLine.SPOT);
    }
    private MockHttpServletRequestBuilder nativeRequest(String method, String path, String body, String key, ProductLine product) throws Exception {
        String query = "productLine=" + product + "&timestamp=" + System.currentTimeMillis();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)));
        String signature = keys.sign("secret", method + "\n" + path + "\n" + query + "\n" + hash);
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method),
                path + "?" + query + "&signature=" + signature).header("X-API-Key", key)
                .with(r -> { r.setRemoteAddr("192.0.2.7"); return r; });
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return request;
    }

    @RestController static class AsyncReads {
        private final AuthService auth; private final CompletableFuture<String> pending;
        AsyncReads(AuthService auth, CompletableFuture<String> pending) { this.auth = auth; this.pending = pending; }
        @GetMapping("/api/v1/test/async") CompletableFuture<String> read(@RequestHeader("Authorization") String bearer) {
            auth.authenticateBearer(bearer); return pending;
        }
    }
}
