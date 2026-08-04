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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SURPRISING_WITHDRAWAL_IT_DATABASE_URL", matches = ".+")
class CustodyWithdrawalReconciliationPostgresTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
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
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new CustodyWithdrawalRepository(jdbcTemplate);
        walletClient = Mockito.mock(CustodyWalletClient.class);
        spotAccountClient = Mockito.mock(SpotAccountClient.class);
        CustodyWithdrawalRefundService refundService =
                new CustodyWithdrawalRefundService(repository, spotAccountClient);
        reconciliationService = new CustodyWithdrawalReconciliationService(
                repository, walletClient, refundService, new ObjectMapper());
        createSchema();
        withdrawalId = insertFailurePendingWithdrawal();
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", withdrawalId);
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

        Future<?> reconciliation = executor.submit(() -> inTransaction(
                () -> reconciliationService.reconcile(record)));
        Thread.sleep(100L);
        assertThat(reconciliation.isDone()).isFalse();
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

        Future<?> reconciliation = executor.submit(() -> inTransaction(
                () -> reconciliationService.reconcile(record)));
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

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("SURPRISING_WITHDRAWAL_IT_DATABASE_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_USER", ""));
        dataSource.setPassword(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD", ""));
        return dataSource;
    }

    private void createSchema() {
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
}
