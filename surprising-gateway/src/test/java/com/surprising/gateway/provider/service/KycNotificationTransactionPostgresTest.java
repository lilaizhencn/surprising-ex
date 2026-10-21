package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.repository.ComplianceKycRepository;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.client.KycProvider;
import com.surprising.gateway.provider.auth.client.KycProviderRegistry;
import com.surprising.gateway.provider.repository.SecurityEmailNotificationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class KycNotificationTransactionPostgresTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private ComplianceService service;
    private ComplianceKycRepository profiles;
    private SecurityNotificationService notifications;
    private final List<String> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "kyc_notification_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO gateway_users VALUES (7),(42)");
        String init = Files.readString(Path.of("../init.sql"));
        for (String table : List.of("gateway_user_kyc_profiles", "gateway_kyc_provider_events")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        for (String statement : Files.readString(Path.of("../deployment/migrations/20261021-security-notification-outbox.sql")).split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        profiles = new ComplianceKycRepository(jdbc, new ObjectMapper());
        profiles.upsert(42, 7, update("PENDING"), Instant.now().minusSeconds(1));
        notifications = new SecurityNotificationService(new SecurityEmailNotificationRepository(jdbc),
                (recipient, subject, body) -> delivered.add(subject));
        var users = mock(AuthPersistenceService.class);
        when(users.user(42)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                42, "user", "user@example.test", "NORMAL", List.of("USER"), Instant.now())));
        var approvals = mock(AdminApprovalService.class);
        when(approvals.requireWrite(eq("Bearer admin"), eq("admin.compliance.write"), eq("gateway-admin"), any(), any()))
                .thenReturn(new AuthModels.JwtPrincipal(7, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600)));
        var providers = mock(KycProviderRegistry.class);
        when(providers.verifyCallback(eq("SELF"), any(), any()))
                .thenReturn(Optional.of(new KycProvider.KycProviderResult(null, "42", "event-1", "VERIFIED", null)));
        var target = new ComplianceService(mock(), profiles, new com.surprising.gateway.provider.auth.repository.KycProviderEventRepository(jdbc), mock(), mock(), mock(AuthService.class),
                approvals, mock(KycDocumentService.class), mock(AdminAuditRepository.class), users,
                notifications, providers, mock());
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ComplianceService.class, () -> target);
        context.refresh();
        service = context.getBean(ComplianceService.class);
    }

    @AfterEach
    void cleanUp() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void adminReviewRollsBackWhenItsNotificationIntentCannotBeStored() {
        rejectNotification();

        assertThatThrownBy(() -> service.adminUpsertKyc("Bearer admin", 42, update("VERIFIED"), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(profiles.find(42).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isZero();
        assertThat(delivered).isEmpty();
    }

    @Test
    void callbackFailureRollsBackItsDeduplicationMarkerAndTheSameEventCanBeRetried() {
        rejectNotification();
        assertThatThrownBy(() -> service.applyProviderCallback("SELF", Map.of(), new byte[0]))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(profiles.find(42).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_kyc_provider_events", Integer.class)).isZero();

        jdbc.execute("ALTER TABLE gateway_security_email_notifications DROP CONSTRAINT reject_kyc_notification");
        assertThat(service.applyProviderCallback("SELF", Map.of(), new byte[0]).status()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_kyc_provider_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isEqualTo(1);
        assertThat(delivered).isEmpty();
    }

    @Test
    void committedReviewQueuesItsResultAndOnlyBackgroundDeliverySendsTheEmail() {
        assertThat(service.adminUpsertKyc("Bearer admin", 42, update("VERIFIED"), null, null).status()).isEqualTo("VERIFIED");
        assertThat(delivered).isEmpty();
        notifications.deliverDue();
        assertThat(delivered).containsExactly("Surprising identity verification update");
    }

    private void rejectNotification() {
        jdbc.execute("ALTER TABLE gateway_security_email_notifications ADD CONSTRAINT reject_kyc_notification CHECK (subject <> 'Surprising identity verification update')");
    }

    private KycUpdateRequest update(String status) {
        return new KycUpdateRequest("STANDARD", status, "SG", "PASSPORT", "SELF", null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
}
