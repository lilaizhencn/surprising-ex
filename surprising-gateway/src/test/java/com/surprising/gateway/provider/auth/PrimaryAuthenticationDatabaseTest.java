package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.controller.AuthClientMetadataBinding;
import com.surprising.gateway.provider.config.GatewayProperties;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.GatewayRefreshSessionRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.EmailVerificationService;
import com.surprising.gateway.provider.auth.service.JwtTokenService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.PasswordHasher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

@EnabledIfEnvironmentVariable(named = "TELEGRAM_TEST_JDBC_URL", matches = ".+")
class PrimaryAuthenticationDatabaseTest {
    JdbcTemplate admin, jdbc;
    String schema;
    GatewayRefreshSessionRepository sessions;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("TELEGRAM_TEST_JDBC_URL");
        String user = System.getenv("TELEGRAM_TEST_DB_USER"), password = System.getenv("TELEGRAM_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "primary_auth_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id bigint PRIMARY KEY)");
        jdbc.execute("INSERT INTO gateway_users VALUES (1),(2)");
        String init = Files.readString(Path.of("../init.sql"));
        int start = init.indexOf("CREATE TABLE IF NOT EXISTS gateway_refresh_sessions (");
        String definition = init.substring(start, init.indexOf(");", start) + 2);
        // Exercise a real pre-migration session, not a row backfilled as fresh.
        jdbc.execute(definition.replace("    authenticated_at    TIMESTAMPTZ,\n", ""));
        jdbc.update("INSERT INTO gateway_refresh_sessions(user_id,token_hash,expires_at) VALUES(1,'legacy',?)",
                Timestamp.from(Instant.now().plusSeconds(3600)));
        String migration = Files.readString(Path.of("../deployment/migrations/20261020-primary-authentication.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration);
        sessions = new GatewayRefreshSessionRepository(jdbc);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void migrationKeepsLegacyUnknownAndSessionProofRequiresOwnerActiveAndUnexpired() {
        Instant now = Instant.parse("2026-10-20T01:00:00Z");
        var legacy = sessions.find("legacy").orElseThrow();
        assertThat(legacy.authenticatedAt()).isNull();
        assertThat(sessions.authenticatedAt(1, legacy.sessionId(), now)).isEmpty();
        long fresh = sessions.save(1, "fresh", now.plusSeconds(600), "test", "127.0.0.1", null, now);
        assertThat(sessions.find("fresh").orElseThrow().authenticatedAt()).isEqualTo(now);
        assertThat(sessions.authenticatedAt(1, fresh, now)).contains(now);
        assertThat(sessions.authenticatedAt(2, fresh, now)).isEmpty();
        assertThat(sessions.authenticatedAt(1, fresh, now.plusSeconds(600))).isEmpty();
        sessions.revoke(fresh, now.plusSeconds(1));
        assertThat(sessions.authenticatedAt(1, fresh, now.plusSeconds(2))).isEmpty();
    }

    @Test
    void actualRefreshServiceAndDatabaseNeverRenewPrimaryAuthenticationAcrossRotation() {
        var persistence = mock(AuthPersistenceService.class);
        var properties = new GatewayProperties();
        var jwt = mock(JwtTokenService.class);
        var verification = mock(LoginVerificationService.class);
        var auth = new AuthService(properties, persistence, mock(PasswordHasher.class), jwt,
                mock(EmailVerificationService.class), verification, mock(GatewayUserAccessBlockRepository.class), new AuthClientMetadataBinding(properties));
        when(persistence.user(1)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                1, null, null, "NORMAL", List.of("USER"), Instant.now())));
        when(persistence.credential(1)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                1, null, null, null, null, "NORMAL", Instant.now())));
        when(persistence.refreshSession(anyString())).thenAnswer(i -> sessions.find(i.getArgument(0)));
        when(persistence.consumeRefreshSession(anyLong(), any())).thenAnswer(i -> sessions.consume(i.getArgument(0), i.getArgument(1)));
        when(persistence.saveRefreshSession(anyLong(), anyString(), any(), any(), any(), any(), any(), nullable(Instant.class)))
                .thenAnswer(i -> sessions.save(i.getArgument(0), i.getArgument(1), i.getArgument(2),
                        i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6), i.getArgument(7)));
        var http = new MockHttpServletRequest();
        var loggedIn = (AuthModels.AuthResponse) auth.loginTelegram(1, new AuthClientMetadataBinding(properties).bind(http));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE authenticated_at IS NOT NULL", Long.class))
                .isEqualTo(1);
        Instant original = Instant.parse("2026-10-20T00:00:00Z");
        jdbc.update("UPDATE gateway_refresh_sessions SET authenticated_at=? WHERE authenticated_at IS NOT NULL", Timestamp.from(original));
        var refreshed = auth.refresh(new AuthModels.RefreshRequest(loggedIn.refreshToken()), new AuthClientMetadataBinding(properties).bind(http));
        auth.refresh(new AuthModels.RefreshRequest(refreshed.refreshToken()), new AuthClientMetadataBinding(properties).bind(http));
        var times = jdbc.query("SELECT authenticated_at FROM gateway_refresh_sessions WHERE authenticated_at IS NOT NULL",
                (rs, n) -> rs.getTimestamp(1).toInstant());
        assertThat(times).hasSize(3).containsOnly(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE revoked_at IS NULL AND authenticated_at IS NOT NULL", Long.class))
                .isEqualTo(1);
    }
}
