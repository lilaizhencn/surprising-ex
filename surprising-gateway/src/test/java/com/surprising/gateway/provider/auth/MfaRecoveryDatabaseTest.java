package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.client.EmailMessageSender;
import com.surprising.gateway.provider.auth.client.SmsMessageSender;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceKycRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserMfaRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.repository.MfaRecoveryRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.MfaRecoveryService;
import com.surprising.gateway.provider.auth.service.PasswordHasher;

import com.surprising.gateway.provider.auth.repository.GatewayAuthChallengeRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentService;
import com.surprising.gateway.provider.service.SecurityNotificationService;
import com.surprising.gateway.provider.repository.SecurityEmailNotificationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class MfaRecoveryDatabaseTest {
    private static final long USER_ID = 701L;
    private static final long ADMIN_ID = 702L;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private MfaRecoveryService service;
    private GatewayUserMfaRepository mfa;
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private SecurityNotificationService notifications;
    private AdminAuditRepository audit;
    private final EmailCapture email = new EmailCapture();
    private final AtomicReference<String> smsCode = new AtomicReference<>();
    private final AtomicReference<String> smsDestination = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "mfa_recovery_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE gateway_users (
                    user_id BIGINT PRIMARY KEY, username TEXT, email TEXT, phone TEXT,
                    password_hash TEXT NOT NULL, status TEXT NOT NULL, email_verified_at TIMESTAMPTZ,
                    withdrawal_restricted_until TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE gateway_auth_challenges (
                    challenge_id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
                    purpose TEXT NOT NULL, channel TEXT NOT NULL, destination TEXT NOT NULL,
                    code_hash TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0, request_ip INET,
                    consumed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT gateway_auth_challenges_purpose_check CHECK
                      (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'LOGIN', 'SENSITIVE_ACTION')),
                    CONSTRAINT gateway_auth_challenges_channel_check CHECK (channel IN ('EMAIL', 'PHONE')),
                    CONSTRAINT gateway_auth_challenges_attempts_check CHECK (attempts BETWEEN 0 AND 5)
                )
                """);
        jdbc.execute("""
                CREATE TABLE gateway_user_mfa (
                    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
                    totp_secret_ciphertext TEXT NOT NULL, enabled BOOLEAN NOT NULL,
                    verified_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL, last_login_step BIGINT NOT NULL DEFAULT -1
                )
                """);
        for (String statement : Files.readString(Path.of("../deployment/migrations/20261006-account-security-hardening.sql"))
                .split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        String init = Files.readString(Path.of("../init.sql"));
        int auditStart = init.indexOf("CREATE TABLE IF NOT EXISTS gateway_admin_operation_logs (");
        jdbc.execute(init.substring(auditStart, init.indexOf("\n);", auditStart) + 3));
        for (String statement : Files.readString(Path.of("../deployment/migrations/20261021-security-notification-outbox.sql")).split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        audit = new AdminAuditRepository(jdbc);
        notifications = new SecurityNotificationService(new SecurityEmailNotificationRepository(jdbc), email);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO gateway_users(user_id, username, email, phone, password_hash, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", USER_ID, "recover-user", "recover@example.test",
                "+12025550123", "hash", "NORMAL", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO gateway_users(user_id, username, email, phone, password_hash, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ADMIN_ID, "reviewer", "reviewer@example.test",
                null, "admin-hash", "NORMAL", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO gateway_user_mfa(user_id, totp_secret_ciphertext, enabled, verified_at, created_at, updated_at)"
                + " VALUES (?, ?, TRUE, ?, ?, ?)", USER_ID, "encrypted-secret", Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));

        var users = mock(AuthPersistenceService.class);
        mfa = new GatewayUserMfaRepository(jdbc);
        when(users.credential(USER_ID)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                USER_ID, "recover-user", "recover@example.test", "+12025550123", "hash", "NORMAL", now)));
        when(users.mfaCredential(USER_ID)).thenAnswer(call -> mfa.find(USER_ID));
        when(users.user(USER_ID)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                USER_ID, "recover-user", "recover@example.test", "NORMAL", List.of("USER"), now)));
        doAnswer(call -> { mfa.disable(USER_ID, call.getArgument(1)); return null; })
                .when(users).disableMfa(eq(USER_ID), any());
        doAnswer(call -> {
            Instant changedAt = call.getArgument(1);
            jdbc.update("UPDATE gateway_users SET withdrawal_restricted_until = ? WHERE user_id = ?",
                    Timestamp.from(changedAt.plusSeconds(86400)), USER_ID);
            return null;
        }).when(users).recordSecurityChange(eq(USER_ID), any());

        var kyc = mock(ComplianceKycRepository.class);
        when(kyc.find(USER_ID)).thenReturn(new KycProfile(USER_ID, "BASIC", "VERIFIED", "US", "PASSPORT",
                "SELF", null, ADMIN_ID, now, null, null, now, now));
        var documents = mock(KycDocumentService.class);
        when(documents.findForUser(USER_ID)).thenReturn(List.of(new KycDocument(
                1, USER_ID, "PASSPORT", "passport.png", "image/png", 100, "sha", "SUBMITTED", now, null)));
        var verification = mock(LoginVerificationService.class);
        when(verification.settings(USER_ID)).thenReturn(List.of(
                new LoginVerificationModels.Setting("EMAIL", true, true, "r***@example.test"),
                new LoginVerificationModels.Setting("PHONE", true, true, "+***0123")));
        var properties = new GatewayProperties();
        properties.getSecurity().setVerificationCodePepper("database-test-pepper");
        var passwords = mock(PasswordHasher.class);
        when(passwords.matches("test-password", "hash")).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<SmsMessageSender> smsProvider = mock(ObjectProvider.class);
        when(smsProvider.getIfAvailable()).thenReturn((phone, code) -> {
            smsDestination.set(phone);
            smsCode.set(code);
        });
        service = transactional(new MfaRecoveryService(users, new GatewayAuthChallengeRepository(jdbc), verification,
                new MfaRecoveryRepository(jdbc), kyc, documents, passwords, properties, email, smsProvider, audit, notifications));
    }

    @AfterEach
    void cleanUp() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void emailRecoveryUsesPersistedChallengeAndManualApprovalChangesMfaAndWithdrawalState() {
        Instant now = Instant.now();
        var challenge = service.issue(USER_ID, "test-password", "127.0.0.1", now);
        assertThat(challenge.channel()).isEqualTo("EMAIL");
        assertThat(email.recipient.get()).isEqualTo("recover@example.test");
        assertThat(email.code.get()).matches("\\d{6}");

        var request = service.submit(USER_ID, new MfaRecoveryService.RecoverySubmission(
                challenge.challengeId(), "test-password", email.code.get(), "I lost my authenticator"), now);
        assertThat(request.status()).isEqualTo("PENDING");
        assertThat(service.pending()).extracting(MfaRecoveryRepository.RecoveryRequest::requestId)
                .contains(request.requestId());
        assertThat(mfa.find(USER_ID).orElseThrow().enabled()).isTrue();

        assertThat(service.decide(request.requestId(), administrator(), true,
                "identity review passed", "test", "127.0.0.1", now).status()).isEqualTo("APPROVED");
        assertThat(mfa.find(USER_ID).orElseThrow().enabled()).isFalse();
        assertThat(mfa.find(USER_ID).orElseThrow().verifiedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT withdrawal_restricted_until > ? FROM gateway_users WHERE user_id = ?",
                Boolean.class, Timestamp.from(now), USER_ID)).isTrue();
        assertThat(email.subjects).doesNotContain("Surprising authenticator recovery update");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs", Integer.class)).isEqualTo(1);
        notifications.deliverDue();
        assertThat(email.subjects).contains("Surprising authenticator recovery update");
    }

    @Test
    void phoneRecoverySimulatesSmsCodeAndRejectedReviewDoesNotDisableMfa() {
        // Mark email as not bound to prove the PHONE factor determines the challenge channel.
        whenPhoneOnly();
        Instant now = Instant.now();
        var challenge = service.issue(USER_ID, "test-password", "127.0.0.1", now);
        assertThat(challenge.channel()).isEqualTo("PHONE");
        assertThat(challenge.destination()).isEqualTo("***0123");
        assertThat(smsDestination.get()).isEqualTo("+12025550123");
        assertThat(smsCode.get()).matches("\\d{6}");

        var request = service.submit(USER_ID, new MfaRecoveryService.RecoverySubmission(
                challenge.challengeId(), "test-password", smsCode.get(), "The device was replaced"), now);
        assertThat(request.status()).isEqualTo("PENDING");
        assertThat(service.decide(request.requestId(), administrator(), false,
                "documents need clarification", "test", "127.0.0.1", now).status()).isEqualTo("REJECTED");
        assertThat(mfa.find(USER_ID).orElseThrow().enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT withdrawal_restricted_until FROM gateway_users WHERE user_id = ?",
                Timestamp.class, USER_ID)).isNull();
    }

    @Test
    void requiredAuditFailureRollsBackReviewMfaAndWithdrawalHold() {
        Instant now = Instant.now();
        var pending = new MfaRecoveryRepository(jdbc).create(USER_ID, "lost authenticator", now);
        jdbc.execute("ALTER TABLE gateway_admin_operation_logs ADD CONSTRAINT reject_audit CHECK (service <> 'gateway')");

        assertThatThrownBy(() -> service.decide(pending.requestId(), administrator(), true,
                "identity review passed", "test", "127.0.0.1", now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertDecisionRolledBack();
    }

    @Test
    void notificationIntentFailureRollsBackReviewAndRequiredAudit() {
        Instant now = Instant.now();
        var pending = new MfaRecoveryRepository(jdbc).create(USER_ID, "lost authenticator", now);
        jdbc.execute("ALTER TABLE gateway_security_email_notifications ADD CONSTRAINT reject_notification CHECK (subject <> 'Surprising authenticator recovery update')");

        assertThatThrownBy(() -> service.decide(pending.requestId(), administrator(), true,
                "identity review passed", "test", "127.0.0.1", now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertDecisionRolledBack();
    }

    private void assertDecisionRolledBack() {
        assertThat(new MfaRecoveryRepository(jdbc).latestForUser(USER_ID).status()).isEqualTo("PENDING");
        assertThat(mfa.find(USER_ID).orElseThrow().enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT withdrawal_restricted_until FROM gateway_users WHERE user_id=?",
                Timestamp.class, USER_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isZero();
        assertThat(email.subjects).doesNotContain("Surprising authenticator recovery update");
    }

    private void whenPhoneOnly() {
        // Email is present on the profile but is not an eligible bound factor for this test.
        var verification = mock(LoginVerificationService.class);
        when(verification.settings(USER_ID)).thenReturn(List.of(
                new LoginVerificationModels.Setting("EMAIL", false, false, null),
                new LoginVerificationModels.Setting("PHONE", true, true, "+***0123")));
        // Rebuild the service with the same database repositories and sender fakes.
        var users = mock(AuthPersistenceService.class);
        Instant now = Instant.now();
        when(users.credential(USER_ID)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                USER_ID, "recover-user", "recover@example.test", "+12025550123", "hash", "NORMAL", now)));
        when(users.mfaCredential(USER_ID)).thenAnswer(call -> mfa.find(USER_ID));
        when(users.user(USER_ID)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                USER_ID, "recover-user", "recover@example.test", "NORMAL", List.of("USER"), now)));
        doAnswer(call -> { mfa.disable(USER_ID, call.getArgument(1)); return null; })
                .when(users).disableMfa(eq(USER_ID), any());
        doAnswer(call -> null).when(users).recordSecurityChange(eq(USER_ID), any());
        var kyc = mock(ComplianceKycRepository.class);
        when(kyc.find(USER_ID)).thenReturn(new KycProfile(USER_ID, "BASIC", "VERIFIED", "US", "PASSPORT",
                "SELF", null, ADMIN_ID, now, null, null, now, now));
        var documents = mock(KycDocumentService.class);
        when(documents.findForUser(USER_ID)).thenReturn(List.of(new KycDocument(
                1, USER_ID, "PASSPORT", "passport.png", "image/png", 100, "sha", "SUBMITTED", now, null)));
        var properties = new GatewayProperties();
        properties.getSecurity().setVerificationCodePepper("database-test-pepper");
        var passwords = mock(PasswordHasher.class);
        when(passwords.matches("test-password", "hash")).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<SmsMessageSender> smsProvider = mock(ObjectProvider.class);
        when(smsProvider.getIfAvailable()).thenReturn((phone, code) -> {
            smsDestination.set(phone);
            smsCode.set(code);
        });
        service = transactional(new MfaRecoveryService(users, new GatewayAuthChallengeRepository(jdbc), verification,
                new MfaRecoveryRepository(jdbc), kyc, documents, passwords, properties, email, smsProvider, audit, notifications));
    }

    private AuthModels.JwtPrincipal administrator() {
        return new AuthModels.JwtPrincipal(ADMIN_ID, "reviewer", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600));
    }

    private MfaRecoveryService transactional(MfaRecoveryService target) {
        if (context != null) context.close();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(MfaRecoveryService.class, () -> target);
        context.refresh();
        return context.getBean(MfaRecoveryService.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    private static final class EmailCapture implements EmailMessageSender {
        private final AtomicReference<String> recipient = new AtomicReference<>();
        private final AtomicReference<String> code = new AtomicReference<>();
        private final List<String> subjects = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void send(String recipient, String subject, String text) {
            this.recipient.set(recipient);
            subjects.add(subject);
            var matcher = java.util.regex.Pattern.compile("\\b\\d{6}\\b").matcher(text);
            if (matcher.find()) code.set(matcher.group());
        }
    }
}
