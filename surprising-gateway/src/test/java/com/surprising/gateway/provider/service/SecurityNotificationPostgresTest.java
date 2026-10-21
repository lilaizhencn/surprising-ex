package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.auth.EmailMessageSender;
import com.surprising.gateway.provider.auth.MfaRecoveryRepository.RecoveryRequest;
import com.surprising.gateway.provider.repository.SecurityEmailNotificationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class SecurityNotificationPostgresTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate transactions;
    private SecurityEmailNotificationRepository repository;
    private SecurityNotificationService notifications;
    private final List<String> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "security_notification_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        for (String statement : Files.readString(Path.of("../deployment/migrations/20261021-security-notification-outbox.sql")).split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        repository = new SecurityEmailNotificationRepository(jdbc);
        notifications = new SecurityNotificationService(repository,
                (recipient, subject, body) -> delivered.add(recipient + '|' + subject));
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void resultIsQueuedWithoutSendingAndDeliveredOnlyByTheDeliveryOperation() {
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");

        assertThat(delivered).isEmpty();
        assertThat(status()).isEqualTo("PENDING");
        notifications.deliverDue();

        assertThat(status()).isEqualTo("SENT");
        assertThat(delivered).containsExactly("user@example.test|Surprising authenticator recovery update");
        notifications.deliverDue();
        assertThat(delivered).hasSize(1);
    }

    @Test
    void rollingBackBusinessTransactionRemovesNotificationIntent() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            notifications.mfaRecoveryDecision(decision(1), "user@example.test");
            throw new IllegalStateException("business failed");
        })).hasMessage("business failed");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isZero();
        notifications.deliverDue();
        assertThat(delivered).isEmpty();
    }

    @Test
    void deliveryBoundaryCannotSendAnIntentFromAnUncommittedBusinessTransaction() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class, transactions::getTransactionManager);
            context.registerBean(SecurityNotificationService.class, () -> notifications);
            context.refresh();
            var service = context.getBean(SecurityNotificationService.class);

            transactions.executeWithoutResult(tx -> {
                service.mfaRecoveryDecision(decision(1), "user@example.test");
                service.deliverDue();
                assertThat(delivered).isEmpty();
                tx.setRollbackOnly();
            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isZero();
        assertThat(delivered).isEmpty();
    }

    @Test
    void failedDeliveryIsPersistedAndAnotherServiceInstanceCanRetryIt() {
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");
        EmailMessageSender unavailable = (recipient, subject, body) -> {
            throw new IllegalStateException("provider error contains private recipient");
        };
        new SecurityNotificationService(repository, unavailable).deliverDue();

        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM gateway_security_email_notifications", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_failure FROM gateway_security_email_notifications", String.class))
                .isEqualTo("IllegalStateException");
        jdbc.update("UPDATE gateway_security_email_notifications SET next_attempt_at=now()-interval '1 second'");

        new SecurityNotificationService(new SecurityEmailNotificationRepository(jdbc),
                (recipient, subject, body) -> delivered.add(subject)).deliverDue();

        assertThat(status()).isEqualTo("SENT");
        assertThat(delivered).containsExactly("Surprising authenticator recovery update");
        assertThat(jdbc.queryForObject("SELECT attempts FROM gateway_security_email_notifications", Integer.class)).isEqualTo(2);
    }

    @Test
    void expiredClaimCanBeRecoveredAndStaleAcknowledgementCannotReplaceNewLease() {
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");
        Instant now = Instant.now();
        var original = repository.claim(now).getFirst();
        assertThat(repository.claim(now.plusSeconds(30))).isEmpty();
        var recovered = repository.claim(now.plusSeconds(61)).getFirst();

        assertThat(recovered.notificationId()).isEqualTo(original.notificationId());
        assertThat(recovered.leaseId()).isNotEqualTo(original.leaseId());
        repository.complete(original, now.plusSeconds(62));
        repository.retry(original, now.plusSeconds(120), "stale worker");
        assertThat(status()).isEqualTo("SENDING");

        repository.complete(recovered, now.plusSeconds(63));
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    void concurrentClaimersCannotOwnTheSameUnexpiredNotification() throws Exception {
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return repository.claim(Instant.now()); });
            var second = executor.submit(() -> { start.await(); return repository.claim(Instant.now()); });
            start.countDown();
            var firstClaim = first.get(5, TimeUnit.SECONDS);
            var secondClaim = second.get(5, TimeUnit.SECONDS);
            assertThat(firstClaim.size() + secondClaim.size()).isEqualTo(1);
        }
    }

    @Test
    void eventIdentityIsIdempotentAndItsRecipientAndContentRemainFixed() {
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");
        notifications.mfaRecoveryDecision(decision(1), "user@example.test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_security_email_notifications", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> notifications.mfaRecoveryDecision(decision(1), "another@example.test"))
                .hasMessageContaining("different content");
        assertThat(jdbc.queryForObject("SELECT recipient FROM gateway_security_email_notifications", String.class))
                .isEqualTo("user@example.test");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM gateway_security_email_notifications", String.class);
    }

    private RecoveryRequest decision(long id) {
        Instant now = Instant.now().minusSeconds(1);
        return new RecoveryRequest(id, 42, "APPROVED", "lost authenticator", now, 7L, now,
                "identity verified", now);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
}
