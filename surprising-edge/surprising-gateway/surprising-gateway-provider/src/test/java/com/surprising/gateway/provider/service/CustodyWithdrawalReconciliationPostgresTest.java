package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SURPRISING_WITHDRAWAL_IT_DATABASE_URL", matches = ".+")
class CustodyWithdrawalReconciliationPostgresTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private AnnotationConfigApplicationContext applicationContext;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private CustodyWithdrawalRepository repository;
    private CustodyWalletClient walletClient;
    private SpotAccountClient spotAccountClient;
    private CustodyWithdrawalReconciliationService reconciliationService;
    private CustodyWithdrawalService withdrawalService;
    private UUID withdrawalId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        walletClient = Mockito.mock(CustodyWalletClient.class);
        spotAccountClient = Mockito.mock(SpotAccountClient.class);
        createSchema();
        applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.register(TransactionConfig.class);
        applicationContext.registerBean(DataSource.class, () -> dataSource);
        applicationContext.registerBean(PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        applicationContext.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
        applicationContext.registerBean(CustodyWalletClient.class, () -> walletClient);
        applicationContext.registerBean(SpotAccountClient.class, () -> spotAccountClient);
        applicationContext.registerBean(GatewayProperties.class, GatewayProperties::new);
        applicationContext.registerBean(WithdrawalValuationClient.class,
                () -> Mockito.mock(WithdrawalValuationClient.class));
        applicationContext.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        applicationContext.registerBean(CustodyWithdrawalRepository.class);
        applicationContext.registerBean(CustodyWithdrawalRefundService.class);
        applicationContext.registerBean(CustodyWithdrawalReconciliationService.class);
        applicationContext.registerBean(CustodyWithdrawalService.class);
        applicationContext.refresh();
        repository = applicationContext.getBean(CustodyWithdrawalRepository.class);
        reconciliationService = applicationContext.getBean(CustodyWithdrawalReconciliationService.class);
        withdrawalService = applicationContext.getBean(CustodyWithdrawalService.class);
        PlatformTransactionManager transactionManager =
                applicationContext.getBean(PlatformTransactionManager.class);
        transactionTemplate = new TransactionTemplate(transactionManager);
        withdrawalId = insertFailurePendingWithdrawal();
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_events "
                    + "DISABLE TRIGGER gateway_wallet_withdrawal_events_immutable_trigger");
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawal_events WHERE withdrawal_id = ?", withdrawalId);
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", withdrawalId);
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_events "
                    + "ENABLE TRIGGER gateway_wallet_withdrawal_events_immutable_trigger");
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
        executor.shutdownNow();
    }

    @Test
    void confirmationThatWinsTheOutcomeLockDoesNotCreditSpot() throws Exception {
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20))
                .thenReturn(List.of(walletRow("CONFIRMED")));
        CustodyWithdrawalRepository.WithdrawalRecord record = repository.find(withdrawalId);
        CountDownLatch confirmationUpdated = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);

        Future<?> confirmation = executor.submit(() -> inTransaction(() -> {
            repository.lockForOutcome(withdrawalId);
            repository.markCompleted(withdrawalId, "{}", "wallet-integration");
            confirmationUpdated.countDown();
            await(releaseConfirmation);
        }));
        assertThat(confirmationUpdated.await(2, TimeUnit.SECONDS)).isTrue();

        Future<?> reconciliation = executor.submit(() -> reconciliationService.reconcile(record));
        releaseConfirmation.countDown();
        confirmation.get(5, TimeUnit.SECONDS);
        reconciliation.get(5, TimeUnit.SECONDS);

        verify(spotAccountClient, never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void refundDecisionHoldsOutcomeLockUntilSpotCreditAndStateCommit() throws Exception {
        CountDownLatch custodyReadStarted = new CountDownLatch(1);
        CountDownLatch allowCustodyRead = new CountDownLatch(1);
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20))
                .thenAnswer(invocation -> {
                    custodyReadStarted.countDown();
                    await(allowCustodyRead);
                    return List.of(walletRow("FAILED"));
                });
        CustodyWithdrawalRepository.WithdrawalRecord record = repository.find(withdrawalId);

        Future<?> reconciliation = executor.submit(() -> reconciliationService.reconcile(record));
        assertThat(custodyReadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Future<?> confirmation = executor.submit(() -> inTransaction(
                () -> repository.markCompleted(withdrawalId, "{}", "wallet-integration")));
        allowCustodyRead.countDown();
        reconciliation.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> confirmation.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(spotAccountClient).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("REFUNDED");
    }

    @Test
    void invalidCompletionSourceDoesNotChangeWithdrawalState() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'PENDING_APPROVAL' WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> repository.markCompleted(withdrawalId, "{}", "wallet-integration"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void conditionalTransitionsRejectExistingWithdrawalInInvalidState() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'COMPLETED' WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> repository.markDebited(withdrawalId, "debit"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markDebitUnknown(withdrawalId, "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markSubmitted(withdrawalId, "{}", "wallet-integration"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markBroadcastUnknown(withdrawalId, "{}", "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markRejected(withdrawalId, "INVALID", "invalid"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void repeatedTargetTransitionsAreIdempotentButAdminTransitionsRemainStrict() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'PROCESSING' WHERE withdrawal_id = ?",
                withdrawalId);

        repository.markDebited(withdrawalId, "debit");
        repository.markDebited(withdrawalId, "debit");
        repository.markSubmitted(withdrawalId, "{\"id\":\"wallet-integration\"}", "wallet-integration");
        assertThatThrownBy(() -> repository.markSubmitted(
                withdrawalId, "{\"id\":\"different\"}", "different"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isEqualTo("wallet-integration");

        assertThatThrownBy(() -> repository.approve(withdrawalId, 99L, "admin", "late approval"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.reject(withdrawalId, 99L, "admin", "late rejection"))
                .isInstanceOf(IllegalStateException.class);

        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'PENDING_APPROVAL' WHERE withdrawal_id = ?",
                withdrawalId);
        repository.markRejected(withdrawalId, "REJECTED", "rejected");
        repository.markRejected(withdrawalId, "REJECTED", "duplicate rejection");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("REJECTED");
    }

    @Test
    void submittedWithdrawalCannotBeDowngradedToBroadcastUnknown() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'SUBMITTED' WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> repository.markBroadcastUnknown(withdrawalId, "{}", "late timeout"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void invalidWebhookDoesNotPersistWalletIdBinding() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET wallet_withdrawal_id = NULL WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED", Map.of(
                "data", Map.of("withdrawalId", "wallet-invalid",
                        "externalReference", "custody-wallet-withdrawal:integration",
                        "asset", "USDT", "chain", "ETH", "amount", "26"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isNull();
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void ambiguousCustodyResultDoesNotCompleteOrRefundWithdrawal() {
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20))
                .thenReturn(List.of(walletRow("CONFIRMED"), walletRow("FAILED", "wallet-other")));

        reconciliationService.reconcile(repository.find(withdrawalId));

        verify(spotAccountClient, never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isEqualTo("wallet-integration");
    }

    @Test
    void duplicateCustodyResultOnLaterPageDoesNotCompleteOrRefundWithdrawal() {
        List<Map<String, Object>> firstPage = new ArrayList<>();
        firstPage.add(walletRow("CONFIRMED"));
        for (int index = 0; index < 19; index++) {
            firstPage.add(Map.of("id", "unrelated-" + index, "externalReference", "unrelated-" + index));
        }
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20))
                .thenReturn(firstPage);
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20, 20))
                .thenReturn(List.of(walletRow("FAILED", "wallet-other")));

        reconciliationService.reconcile(repository.find(withdrawalId));

        verify(spotAccountClient, never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void custodyResultWithMismatchedAmountDoesNotCompleteOrRefundWithdrawal() {
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20))
                .thenReturn(List.of(Map.of(
                        "id", "wallet-integration",
                        "externalReference", "custody-wallet-withdrawal:integration",
                        "status", "CONFIRMED", "asset", "USDT", "chain", "ETH", "amount", "26")));

        reconciliationService.reconcile(repository.find(withdrawalId));

        verify(spotAccountClient, never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void webhookBindsWalletIdByExternalReferenceAndRejectsConflictingBinding() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET wallet_withdrawal_id = NULL WHERE withdrawal_id = ?",
                withdrawalId);
        UUID otherId = insertWithdrawal("custody-wallet-withdrawal:other", "wallet-owned");

        try {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE gateway_wallet_withdrawals SET wallet_withdrawal_id = 'wallet-owned' WHERE withdrawal_id = ?",
                    withdrawalId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> repository.findByWalletReference(
                    "wallet-owned", "custody-wallet-withdrawal:integration"))
                    .isInstanceOf(IllegalStateException.class);

            CustodyWithdrawalRepository.WithdrawalRecord bound = repository.findByWalletReference(
                    "wallet-bound", "custody-wallet-withdrawal:integration");
            assertThat(bound.walletWithdrawalId()).isEqualTo("wallet-bound");
            assertThat(repository.find(withdrawalId).walletWithdrawalId()).isEqualTo("wallet-bound");
            assertThatThrownBy(() -> repository.findByWalletReference(
                    "wallet-other", "custody-wallet-withdrawal:integration"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", otherId);
        }
    }

    @Test
    void stateTransitionRejectsWalletIdOwnedByAnotherWithdrawal() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'DEBITED', wallet_withdrawal_id = NULL "
                + "WHERE withdrawal_id = ?", withdrawalId);
        UUID otherId = insertWithdrawal("custody-wallet-withdrawal:other-state", "wallet-owned-state");

        try {
            assertThatThrownBy(() -> repository.markSubmitted(
                    withdrawalId, "{}", "wallet-owned-state"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(repository.find(withdrawalId).status()).isEqualTo("DEBITED");
            assertThat(repository.find(withdrawalId).walletWithdrawalId()).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", otherId);
        }
    }

    @Test
    void duplicateCompletionForSameTargetIsIdempotent() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'SUBMITTED' WHERE withdrawal_id = ?",
                withdrawalId);

        repository.markCompleted(withdrawalId, "{}", "wallet-integration");
        repository.markCompleted(withdrawalId, "{}", "wallet-integration");

        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void automaticStateChangesAppendImmutableWithdrawalEvents() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'DEBITED' WHERE withdrawal_id = ?",
                withdrawalId);

        repository.markSubmitted(withdrawalId, "{\"id\":\"wallet-integration\"}", "wallet-integration");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gateway_wallet_withdrawal_events
                 WHERE withdrawal_id = ? AND event_type = 'SUBMITTED'
                """, Integer.class, withdrawalId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT from_status || ':' || to_status FROM gateway_wallet_withdrawal_events
                 WHERE withdrawal_id = ? AND event_type = 'SUBMITTED'
                """, String.class, withdrawalId)).isEqualTo("DEBITED:SUBMITTED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawal_events SET reason = 'tampered' WHERE withdrawal_id = ?
                """, withdrawalId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM gateway_wallet_withdrawal_events WHERE withdrawal_id = ?", withdrawalId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gateway_wallet_withdrawal_events
                 WHERE withdrawal_id = ? AND event_type = 'SUBMITTED'
                """, Integer.class, withdrawalId)).isEqualTo(1);
    }

    @Test
    void adminStateAndActionAuditRollbackTogetherWhenAuditInsertFails() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'PENDING_APPROVAL' WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> repository.approve(withdrawalId, 99L, "admin", "manual approval"))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void retryCannotBypassPendingApproval() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'PENDING_APPROVAL' WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> repository.recordAdminRetry(withdrawalId, 99L, "admin", "manual retry"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("SURPRISING_WITHDRAWAL_IT_DATABASE_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_USER", ""));
        dataSource.setPassword(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD", ""));
        return dataSource;
    }

    private void createSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_users (user_id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawals ("
                + "withdrawal_id UUID PRIMARY KEY, user_id BIGINT NOT NULL, idempotency_key TEXT NOT NULL, "
                + "request_sha256 TEXT NOT NULL, chain TEXT NOT NULL, asset_symbol TEXT NOT NULL, "
                + "custody_address_id UUID NOT NULL, to_address TEXT NOT NULL, amount TEXT NOT NULL, "
                + "amount_units BIGINT NOT NULL, usdt_value NUMERIC(38,18) NOT NULL, external_reference TEXT NOT NULL, "
                + "spot_debit_reference TEXT NOT NULL, request_payload JSONB NOT NULL, status TEXT NOT NULL, "
                + "wallet_response JSONB, wallet_withdrawal_id TEXT, error_code TEXT, error_message TEXT, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "submitted_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, admin_user_id BIGINT, "
                + "admin_username TEXT, admin_reason TEXT, "
                + "CONSTRAINT gateway_wallet_withdrawals_wallet_id_uq UNIQUE (wallet_withdrawal_id))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_actions ("
                + "action_id UUID PRIMARY KEY, withdrawal_id UUID NOT NULL REFERENCES gateway_wallet_withdrawals, "
                + "admin_user_id BIGINT NOT NULL REFERENCES gateway_users, admin_username TEXT NOT NULL, "
                + "action TEXT NOT NULL, reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_events ("
                + "event_id UUID PRIMARY KEY, withdrawal_id UUID NOT NULL REFERENCES gateway_wallet_withdrawals, "
                + "event_type TEXT NOT NULL, source TEXT NOT NULL, from_status TEXT, to_status TEXT, "
                + "wallet_withdrawal_id TEXT, payload JSONB NOT NULL DEFAULT '{}'::jsonb, reason TEXT, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_events_immutable_guard()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'gateway wallet withdrawal events are immutable';
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_events_immutable_trigger "
                + "ON gateway_wallet_withdrawal_events");
        jdbcTemplate.execute("CREATE TRIGGER gateway_wallet_withdrawal_events_immutable_trigger "
                + "BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_events FOR EACH ROW "
                + "EXECUTE FUNCTION gateway_wallet_withdrawal_events_immutable_guard()");
    }

    private UUID insertFailurePendingWithdrawal() {
        return insertWithdrawal("custody-wallet-withdrawal:integration", "wallet-integration");
    }

    private UUID insertWithdrawal(String externalReference, String walletWithdrawalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawals (
                    withdrawal_id, user_id, idempotency_key, request_sha256, chain, asset_symbol,
                    custody_address_id, to_address, amount, amount_units, usdt_value, external_reference,
                    spot_debit_reference, request_payload, status, wallet_withdrawal_id
                ) VALUES (?, 42, ?, ?, 'ETH', 'USDT', ?, '0xrecipient', '25',
                          25000000, 25, ?, ?, '{}'::jsonb, 'FAILED_PENDING', ?)
                """, id, externalReference + ":key", "hash-" + externalReference, UUID.randomUUID(),
                externalReference, externalReference, walletWithdrawalId);
        return id;
    }

    private Map<String, Object> walletRow(String status) {
        return walletRow(status, "wallet-integration");
    }

    private Map<String, Object> walletRow(String status, String walletWithdrawalId) {
        return Map.of("id", walletWithdrawalId, "externalReference", "custody-wallet-withdrawal:integration",
                "status", status, "asset", "USDT", "chain", "ETH", "amount", "25");
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.execute(status -> {
            action.run();
            return null;
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("integration test coordination timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("integration test coordination interrupted", ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
    }
}
