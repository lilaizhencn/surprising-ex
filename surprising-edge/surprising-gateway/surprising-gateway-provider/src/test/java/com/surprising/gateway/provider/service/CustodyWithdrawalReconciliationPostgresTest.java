package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
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
        applicationContext.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        applicationContext.registerBean(CustodyWithdrawalRepository.class);
        applicationContext.registerBean(CustodyWithdrawalRefundService.class);
        applicationContext.registerBean(CustodyWithdrawalReconciliationService.class);
        applicationContext.refresh();
        repository = applicationContext.getBean(CustodyWithdrawalRepository.class);
        reconciliationService = applicationContext.getBean(CustodyWithdrawalReconciliationService.class);
        PlatformTransactionManager transactionManager =
                applicationContext.getBean(PlatformTransactionManager.class);
        transactionTemplate = new TransactionTemplate(transactionManager);
        withdrawalId = insertFailurePendingWithdrawal();
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", withdrawalId);
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
    void duplicateCompletionForSameTargetIsIdempotent() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = 'SUBMITTED' WHERE withdrawal_id = ?",
                withdrawalId);

        repository.markCompleted(withdrawalId, "{}", "wallet-integration");
        repository.markCompleted(withdrawalId, "{}", "wallet-integration");

        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
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
                + "admin_username TEXT, admin_reason TEXT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_actions ("
                + "action_id UUID PRIMARY KEY, withdrawal_id UUID NOT NULL REFERENCES gateway_wallet_withdrawals, "
                + "admin_user_id BIGINT NOT NULL REFERENCES gateway_users, admin_username TEXT NOT NULL, "
                + "action TEXT NOT NULL, reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
    }

    private UUID insertFailurePendingWithdrawal() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawals (
                    withdrawal_id, user_id, idempotency_key, request_sha256, chain, asset_symbol,
                    custody_address_id, to_address, amount, amount_units, usdt_value, external_reference,
                    spot_debit_reference, request_payload, status, wallet_withdrawal_id
                ) VALUES (?, 42, 'integration-key', 'hash', 'ETH', 'USDT', ?, '0xrecipient', '25',
                          25000000, 25, 'custody-wallet-withdrawal:integration',
                          'custody-wallet-withdrawal:integration', '{}'::jsonb, 'FAILED_PENDING', 'wallet-integration')
                """, id, UUID.randomUUID());
        return id;
    }

    private Map<String, Object> walletRow(String status) {
        return Map.of("id", "wallet-integration", "externalReference", "custody-wallet-withdrawal:integration",
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
