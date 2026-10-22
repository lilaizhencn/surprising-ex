package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.controller.UserApiKeyController;
import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
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

/** Actual management HTTP -> business/auth checks -> one-table PostgreSQL repository. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class ApiKeyManagementPostgresTest {
    private static final String PATH = "/api/v1/security/api-keys";
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate admin, jdbc;
    private String schema;
    private MockMvc mvc;
    private AuthService auth;
    private SensitiveActionVerificationService verification;
    private GatewayApiKeyService service;
    private TotpService totp;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "api_management_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        String init = Files.readString(Path.of("../init.sql"));
        for (String table : List.of("gateway_users", "gateway_api_keys")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        jdbc.execute("INSERT INTO gateway_users(user_id,username,password_hash,created_at,updated_at) VALUES(7,'owner','hash',now(),now()),(8,'other','hash',now(),now())");
        var properties = new GatewayProperties();
        auth = mock(AuthService.class); verification = mock(SensitiveActionVerificationService.class);
        for (long id : List.of(7L, 8L)) when(auth.authenticateBearer("Bearer " + id))
                .thenReturn(new JwtPrincipal(id, "user_" + id, "NORMAL", List.of("USER"), Instant.now().plusSeconds(300)));
        when(verification.verify(anyLong(), eq("SECURITY_SETTINGS"), any(), any(), any())).thenReturn(true);
        totp = new TotpService(properties);
        service = new GatewayApiKeyService(new GatewayApiKeyRepository(jdbc), auth, totp, verification, properties);
        mvc = MockMvcBuilders.standaloneSetup(new UserApiKeyController(service, properties)).build();
    }
    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void createNormalizesFieldsStoresEncryptedSecretAndOnlyCreateReturnsTheSecret() throws Exception {
        JsonNode created = create(Map.of("label", "  maker key  ", "permissions", List.of(" trade ", "READ", "TRADE"),
                "ipAllowlist", List.of(" 203.0.113.0/24 ", "::1", "203.0.113.0/24")));
        String key = created.get("apiKey").get("apiKey").asString();
        String secret = created.get("secret").asString();
        assertThat(key).matches("sx_[A-Za-z0-9_-]{20,80}");
        assertThat(created.get("apiKey").get("label").asString()).isEqualTo("maker key");
        assertThat(created.get("apiKey").get("permissions").asString()).isEqualTo("TRADE,READ");
        assertThat(created.get("apiKey").get("ipAllowlist").size()).isEqualTo(2);
        var stored = jdbc.queryForMap("SELECT * FROM gateway_api_keys WHERE api_key=?", key);
        assertThat(stored.get("secret_ciphertext")).isNotEqualTo(secret);
        assertThat(totp.decryptSecret((String) stored.get("secret_ciphertext"))).isEqualTo(secret);
        assertThat(stored.get("ip_allowlist")).isEqualTo("203.0.113.0/24,::1");
        String listed = mvc.perform(get(PATH).header("Authorization", "Bearer 7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(key).doesNotContain(secret, "secret", "secretCiphertext");
        verify(verification).verify(eq(7L), eq("SECURITY_SETTINGS"), eq("email"), eq("123456"), any());
        String canonical = "timestamp=" + System.currentTimeMillis();
        long timestamp = Long.parseLong(canonical.substring("timestamp=".length()));
        assertThat(service.authenticate(new ApiKeyAuthenticationRequest(key, "203.0.113.9", timestamp, 5000,
                service.sign(secret, canonical), canonical), "TRADE")).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM gateway_api_keys WHERE api_key=?", Boolean.class, key)).isTrue();
    }

    @Test void missingPermissionsStillReceiveReadAndEmptyAllowlistStaysUnrestricted() throws Exception {
        JsonNode created = create(Map.of("label", "default"));
        assertThat(created.get("apiKey").get("permissions").asString()).isEqualTo("READ");
        assertThat(created.get("apiKey").get("ipAllowlist").size()).isZero();
    }

    @Test void onlyOwnerCanUpdateOrRevokeAndRevokedKeyCannotAuthenticate() throws Exception {
        JsonNode created = create(Map.of("label", "owner"));
        String key = created.get("apiKey").get("apiKey").asString();
        byte[] update = json.writeValueAsBytes(Map.of("apiKey", key, "ipAllowlist", List.of("203.0.113.7")));
        mvc.perform(secure(patch(PATH), 8).content(update)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT ip_allowlist FROM gateway_api_keys WHERE api_key=?", String.class, key)).isEmpty();
        mvc.perform(secure(patch(PATH), 7).content(update)).andExpect(status().isOk());
        mvc.perform(secure(delete(PATH), 8).content(json.writeValueAsBytes(Map.of("apiKey", key)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_api_keys WHERE api_key=?", String.class, key)).isEqualTo("ACTIVE");
        mvc.perform(secure(delete(PATH), 7).content(json.writeValueAsBytes(Map.of("apiKey", key)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_api_keys WHERE api_key=?", String.class, key)).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.authenticate(new ApiKeyAuthenticationRequest(key, "203.0.113.7", 0, 5000, "x", "x"), "READ"))
                .hasMessage("invalid api key");
        assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer 8")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo("[]");
    }

    @ParameterizedTest @ValueSource(strings = {"create", "patch", "delete"})
    void securityVerificationStillGuardsEveryValidMutation(String operation) throws Exception {
        JsonNode created = create(Map.of("label", "keep"));
        String key = created.get("apiKey").get("apiKey").asString();
        when(verification.verify(anyLong(), any(), any(), any(), any())).thenReturn(false);
        var request = operation.equals("create") ? post(PATH) : operation.equals("patch") ? patch(PATH) : delete(PATH);
        Object body = operation.equals("create") ? Map.of("label", "new") : Map.of("apiKey", key, "ipAllowlist", List.of("203.0.113.7"));
        mvc.perform(secure(request, 7).content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_api_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_api_keys WHERE api_key=?", String.class, key)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT ip_allowlist FROM gateway_api_keys WHERE api_key=?", String.class, key)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"blank-label", "long-label", "unsupported-permission", "null-permission", "hostname", "invalid-cidr", "blank-ip", "101-ips", "invalid-update-key", "invalid-revoke-key"})
    void malformedManagementInputNeverConsumesSecurityVerification(String invalid) throws Exception {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("label", "valid");
        var request = post(PATH);
        switch (invalid) {
            case "blank-label" -> body.put("label", " ");
            case "long-label" -> body.put("label", "x".repeat(81));
            case "unsupported-permission" -> body.put("permissions", List.of("ADMIN"));
            case "null-permission" -> body.put("permissions", Arrays.asList((String) null));
            case "hostname" -> body.put("ipAllowlist", List.of("example.com"));
            case "invalid-cidr" -> body.put("ipAllowlist", List.of("203.0.113.7/33"));
            case "blank-ip" -> body.put("ipAllowlist", List.of(" "));
            case "101-ips" -> body.put("ipAllowlist", Collections.nCopies(101, "203.0.113.7"));
            case "invalid-update-key" -> { request = patch(PATH); body.put("apiKey", "bad"); }
            default -> { request = delete(PATH); body.put("apiKey", "bad"); }
        }
        mvc.perform(secure(request, 7).content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(auth, verification);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_api_keys", Integer.class)).isZero();
    }

    private JsonNode create(Object body) throws Exception {
        return json.readTree(mvc.perform(secure(post(PATH), 7).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private MockHttpServletRequestBuilder secure(MockHttpServletRequestBuilder request, long user) {
        return request.header("Authorization", "Bearer " + user).header("X-Security-Email-Code", "email")
                .header("X-Security-TOTP-Code", "123456").contentType("application/json").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; });
    }
}
