package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.SpotAccountClient;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.client.WithdrawalValuationClient;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
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
import java.util.concurrent.TimeoutException;
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
    private CustodyWithdrawalStateService states;
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
        applicationContext.registerBean(com.surprising.gateway.provider.repository.CustodyWithdrawalEventRepository.class);
        applicationContext.registerBean(com.surprising.gateway.provider.repository.CustodyWithdrawalActionRepository.class);
        applicationContext.registerBean(com.surprising.gateway.provider.repository.CustodyWithdrawalFeeRepository.class);
        applicationContext.registerBean(CustodyWithdrawalStateService.class);
        applicationContext.registerBean(CustodyWithdrawalRefundService.class);
        applicationContext.registerBean(CustodyWithdrawalReconciliationService.class);
        applicationContext.registerBean(com.surprising.asset.service.AssetConfigurationService.class, () -> {
            var config=Mockito.mock(com.surprising.asset.service.AssetConfigurationService.class);
            when(config.lockWithdrawalQuote("USDT","ETH","25")).thenReturn(new com.surprising.asset.service.AssetConfigurationService.WithdrawalQuote("USDT","ETH","25",25_000_000L,"0",0L,"25",25_000_000L,1,0L));
            return config;
        });
        applicationContext.registerBean(CustodyWithdrawalService.class);
        applicationContext.refresh();
        repository = applicationContext.getBean(CustodyWithdrawalRepository.class);
        states = applicationContext.getBean(CustodyWithdrawalStateService.class);
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
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_actions "
                    + "DISABLE TRIGGER gateway_wallet_withdrawal_actions_immutable_trigger");
            for (String table : List.of("gateway_wallet_withdrawal_actions", "gateway_wallet_withdrawal_events")) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE withdrawal_id IN "
                        + "(SELECT withdrawal_id FROM gateway_wallet_withdrawals WHERE user_id = 43)");
            }
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_fees DISABLE TRIGGER gateway_wallet_withdrawal_fees_immutable_trigger");
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawal_fees WHERE user_id IN (42,43)");
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_fees ENABLE TRIGGER gateway_wallet_withdrawal_fees_immutable_trigger");
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE user_id = 43");
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawal_actions WHERE withdrawal_id = ?", withdrawalId);
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawal_events WHERE withdrawal_id = ?", withdrawalId);
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", withdrawalId);
            jdbcTemplate.update("DELETE FROM gateway_users WHERE user_id = 99");
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_actions "
                    + "ENABLE TRIGGER gateway_wallet_withdrawal_actions_immutable_trigger");
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_events "
                    + "ENABLE TRIGGER gateway_wallet_withdrawal_events_immutable_trigger");
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
        executor.shutdownNow();
    }

    @Test
    void repeatedIntentReturnsOriginalAndRejectsChangedPayloadWithoutAnotherEvent() {
        var first = states.createOrGet(intent("intent-repeat", "hash-one", new BigDecimal("50")));
        var repeated = states.createOrGet(intent("intent-repeat", "hash-one", new BigDecimal("50")));
        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.record().withdrawalId()).isEqualTo(first.record().withdrawalId());
        assertThatThrownBy(() -> states.createOrGet(intent("intent-repeat", "hash-two", new BigDecimal("50"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotency key");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gateway_wallet_withdrawal_events "
                + "WHERE withdrawal_id = ?", Integer.class, first.record().withdrawalId())).isEqualTo(1);
    }

    @Test
    void quotaLockSerializesIntentsUntilOriginalTransactionCommits() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> inTransaction(() -> {
            states.createOrGet(intent("quota-first", "hash-first", new BigDecimal("25")));
            firstInserted.countDown();
            await(releaseFirst);
        }));
        assertThat(firstInserted.await(2, TimeUnit.SECONDS)).isTrue();
        Future<?> second = executor.submit(() -> {
            secondStarted.countDown();
            return states.createOrGet(intent("quota-second", "hash-second", new BigDecimal("25")));
        });
        try {
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            releaseFirst.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("daily withdrawal limit exceeded");
        assertThat(repository.dailyTotal(43L)).isEqualByComparingTo("25");
        assertThat(repository.findByUserAndIdempotency(43L, "quota-second")).isNull();
    }

    @Test
    void failedIntentEventLeavesNeitherIntentNorConsumedQuota() {
        rejectEventInserts();
        try {
            assertThatThrownBy(() -> states.createOrGet(intent("intent-event-failed", "hash", new BigDecimal("25"))))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repository.findByUserAndIdempotency(43L, "intent-event-failed")).isNull();
            assertThat(repository.dailyTotal(43L)).isEqualByComparingTo("0");
        } finally {
            allowEventInserts();
        }
        assertThat(states.createOrGet(intent("intent-event-failed", "hash", new BigDecimal("25"))).created()).isTrue();
    }


    @Test
    void coreAuthorizesProfitWithdrawalAndConfirmedNetPaymentBooksTheQuotedFeeOnce() {
        configurePlatformFee();
        var paid=withdrawalService.submit(43L,"pool-profit-fee",poolRequest());
        var record=repository.find(paid.withdrawalId());
        assertThat(record.custodyAddressId()).isNull();
        assertThat(record.fundingSource()).isEqualTo("TENANT_POOL");
        assertThat(record.fees().receivedAmount()).isEqualTo("22.5");
        Mockito.verify(spotAccountClient).adjustBalance(43L,"USDT",-25_000_000L,record.spotDebitReference(),"custody wallet withdrawal");
        var body=CustodyWithdrawalModels.custodyRequest(record);
        assertThat(body).containsEntry("custodyAddressId",null).containsEntry("subject","user:43")
                .containsEntry("fundingSource","TENANT_POOL").containsEntry("amount","22.5");
        var confirmed=new java.util.LinkedHashMap<String,Object>(body);
        confirmed.put("withdrawalId",record.walletWithdrawalId());
        withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",confirmed));
        withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",confirmed));
        assertThat(repository.find(record.withdrawalId()).status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select fee_units from gateway_wallet_withdrawal_fees where withdrawal_id=?",Long.class,record.withdrawalId())).isEqualTo(2_500_000L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from gateway_wallet_withdrawal_fees where withdrawal_id=?",Integer.class,record.withdrawalId())).isEqualTo(1);
        Mockito.verify(spotAccountClient,Mockito.never()).adjustBalance(Mockito.eq(43L),Mockito.eq("USDT"),Mockito.eq(25_000_000L),Mockito.anyString(),Mockito.anyString());
    }

    @Test
    void verifiedFailedNetPaymentRefundsTheFullCoreDebitAndBooksNoPlatformIncome() {
        configurePlatformFee();
        var submitted=withdrawalService.submit(43L,"pool-fee-refund",poolRequest());
        var record=repository.find(submitted.withdrawalId());
        var failed=new java.util.LinkedHashMap<String,Object>(CustodyWithdrawalModels.custodyRequest(record));
        failed.put("id",record.walletWithdrawalId()); failed.put("status","FAILED"); failed.put("refundable",true);
        Mockito.when(walletClient.withdrawalsByExternalReference(record.externalReference(),"ETH","USDT",2)).thenReturn(List.of(failed));
        reconciliationService.reconcile(record);
        reconciliationService.reconcile(repository.find(record.withdrawalId()));
        assertThat(repository.find(record.withdrawalId()).status()).isEqualTo("REFUNDED");
        Mockito.verify(spotAccountClient).adjustBalance(43L,"USDT",25_000_000L,record.spotDebitReference()+":refund","custody wallet withdrawal failed");
        assertThat(jdbcTemplate.queryForObject("select count(*) from gateway_wallet_withdrawal_fees where withdrawal_id=?",Integer.class,record.withdrawalId())).isZero();
    }

    @Test
    void anotherBeneficiaryCannotCompleteOrRefundThisPoolWithdrawal() {
        configurePlatformFee();
        var submitted=withdrawalService.submit(43L,"pool-wrong-owner",poolRequest());
        var record=repository.find(submitted.withdrawalId());
        var wrong=new java.util.LinkedHashMap<String,Object>(CustodyWithdrawalModels.custodyRequest(record));
        wrong.put("id",record.walletWithdrawalId()); wrong.put("withdrawalId",record.walletWithdrawalId());
        wrong.put("subject","user:42"); wrong.put("status","FAILED"); wrong.put("refundable",true);
        assertThatThrownBy(()->withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",wrong))).isInstanceOf(IllegalArgumentException.class);
        Mockito.when(walletClient.withdrawalsByExternalReference(record.externalReference(),"ETH","USDT",2)).thenReturn(List.of(wrong));
        reconciliationService.reconcile(record);
        assertThat(repository.find(record.withdrawalId()).status()).isEqualTo("SUBMITTED");
        Mockito.verify(spotAccountClient,Mockito.never()).adjustBalance(Mockito.eq(43L),Mockito.eq("USDT"),Mockito.eq(25_000_000L),Mockito.anyString(),Mockito.anyString());
        assertThat(jdbcTemplate.queryForObject("select count(*) from gateway_wallet_withdrawal_fees where withdrawal_id=?",Integer.class,record.withdrawalId())).isZero();
    }

    private CustodyWithdrawalService.WithdrawalRequest poolRequest() {
        return new CustodyWithdrawalService.WithdrawalRequest(null,"ETH","USDT","0xrecipient","25",null,0L);
    }
    private void configurePlatformFee() {
        var assets=org.springframework.test.util.AopTestUtils.<com.surprising.asset.service.AssetConfigurationService>getTargetObject(
                applicationContext.getBean(com.surprising.asset.service.AssetConfigurationService.class));
        var quote=new com.surprising.asset.service.AssetConfigurationService.WithdrawalQuote("USDT","ETH","25",25_000_000L,"2.5",2_500_000L,"22.5",22_500_000L,1,0L);
        Mockito.when(assets.withdrawalQuote("USDT","ETH","25")).thenReturn(quote);
        Mockito.when(assets.lockWithdrawalQuote("USDT","ETH","25")).thenReturn(quote);
        Mockito.when(applicationContext.getBean(WithdrawalValuationClient.class).toUsdt("USDT",new BigDecimal("25"))).thenReturn(new BigDecimal("25"));
        Mockito.when(walletClient.createWithdrawal(Mockito.eq(43L),Mockito.anyMap(),Mockito.anyString())).thenAnswer(call->{
            var body=new java.util.LinkedHashMap<String,Object>(call.getArgument(1));
            body.put("id","pool-payment-"+call.getArgument(2)); body.put("status","FROZEN");
            return body;
        });
    }

    private CustodyWithdrawalModels.CreateRequest intent(String key, String hash, BigDecimal dailyLimit) {
        return new CustodyWithdrawalModels.CreateRequest(43L, key, hash, "ETH", "USDT", null,
                "0xrecipient", "25", 25_000_000L, new BigDecimal("25"), "intent:" + key,
                "intent:" + key, "{}", false, dailyLimit, new com.surprising.gateway.provider.model.CustodyWithdrawalModels.FeeSnapshot("0",0L,"25",25_000_000L,1,0L), "TENANT_POOL");
    }

    @Test
    void confirmationThatWinsTheOutcomeLockDoesNotCreditSpot() throws Exception {
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
                .thenReturn(List.of(walletRow("CONFIRMED")));
        CustodyWithdrawalModels.WithdrawalRecord record = repository.find(withdrawalId);
        CountDownLatch confirmationUpdated = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);

        Future<?> confirmation = executor.submit(() -> inTransaction(() -> {
            repository.lockForOutcome(withdrawalId);
            states.markCompleted(withdrawalId, "{}", "wallet-integration");
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
    void confirmationCanFinishWhileRemoteReconciliationIsWaiting() throws Exception {
        CountDownLatch custodyReadStarted = new CountDownLatch(1);
        CountDownLatch allowCustodyRead = new CountDownLatch(1);
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
                .thenAnswer(invocation -> {
                    custodyReadStarted.countDown();
                    await(allowCustodyRead);
                    return List.of(walletRow("FAILED"));
                });
        CustodyWithdrawalModels.WithdrawalRecord record = repository.find(withdrawalId);

        Future<?> reconciliation = executor.submit(() -> reconciliationService.reconcile(record));
        assertThat(custodyReadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Future<?> confirmation = executor.submit(() -> inTransaction(
                () -> states.markCompleted(withdrawalId, "{}", "wallet-integration")));
        confirmation.get(5,TimeUnit.SECONDS);
        allowCustodyRead.countDown();
        assertThatThrownBy(() -> reconciliation.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);

        verify(spotAccountClient,never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void invalidCompletionSourceDoesNotChangeWithdrawalState() {
        forceStatus("PENDING_APPROVAL");

        assertThatThrownBy(() -> states.markCompleted(withdrawalId, "{}", "wallet-integration"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void conditionalTransitionsRejectExistingWithdrawalInInvalidState() {
        forceStatus("COMPLETED");

        assertThatThrownBy(() -> states.markDebited(withdrawalId, "debit"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.markDebitUnknown(withdrawalId, "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.markSubmitted(withdrawalId, "{}", "wallet-integration"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.markBroadcastUnknown(withdrawalId, "{}", "unknown"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.markRejected(withdrawalId, "INVALID", "invalid"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void repeatedTargetTransitionsAreIdempotentButAdminTransitionsRemainStrict() {
        forceStatus("PROCESSING");

        states.markDebited(withdrawalId, "debit");
        states.markDebited(withdrawalId, "debit");
        states.markSubmitted(withdrawalId, "{\"id\":\"wallet-integration\"}", "wallet-integration");
        assertThatThrownBy(() -> states.markSubmitted(
                withdrawalId, "{\"id\":\"different\"}", "different"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isEqualTo("wallet-integration");

        assertThatThrownBy(() -> states.approve(withdrawalId, 99L, "admin", "late approval"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> states.reject(withdrawalId, 99L, "admin", "late rejection"))
                .isInstanceOf(IllegalStateException.class);

        forceStatus("PENDING_APPROVAL");
        states.markRejected(withdrawalId, "REJECTED", "rejected");
        states.markRejected(withdrawalId, "REJECTED", "duplicate rejection");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("REJECTED");
    }

    @Test
    void submittedWithdrawalCannotBeDowngradedToBroadcastUnknown() {
        forceStatus("SUBMITTED");

        assertThatThrownBy(() -> states.markBroadcastUnknown(withdrawalId, "{}", "late timeout"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void lateBroadcastUnknownObservationKeepsProviderEventId() {
        forceStatus("SUBMITTED");

        withdrawalService.handleWebhook("provider-event-late", "WITHDRAWAL.BROADCAST_UNKNOWN", Map.of(
                "data", Map.of("withdrawalId","wallet-integration","externalReference","custody-wallet-withdrawal:integration","asset","USDT","chain","ETH","amount","25","toAddress","0xrecipient","fundingSource","TENANT_POOL","subject","user:42")));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT provider_event_id FROM gateway_wallet_withdrawal_events
                 WHERE withdrawal_id = ? AND event_type = 'WEBHOOK_IDEMPOTENT'
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, withdrawalId)).isEqualTo("provider-event-late");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void invalidWebhookDoesNotPersistWalletIdBinding() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET wallet_withdrawal_id = NULL WHERE withdrawal_id = ?",
                withdrawalId);

        assertThatThrownBy(() -> withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED", Map.of(
                "data", Map.of("withdrawalId","wallet-invalid","externalReference","custody-wallet-withdrawal:integration","asset","USDT","chain","ETH","amount","26","fundingSource","TENANT_POOL","subject","user:42"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isNull();
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void ambiguousCustodyResultDoesNotCompleteOrRefundWithdrawal() {
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
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
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
                .thenReturn(firstPage);
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 20, 20L))
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
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
                .thenReturn(List.of(Map.of("id","wallet-integration","externalReference","custody-wallet-withdrawal:integration","status","CONFIRMED","asset","USDT","chain","ETH","amount","26","fundingSource","TENANT_POOL","subject","user:42")));

        reconciliationService.reconcile(repository.find(withdrawalId));

        verify(spotAccountClient, never()).adjustBalance(
                42L, "USDT", 25_000_000L,
                "custody-wallet-withdrawal:integration:refund", "custody wallet withdrawal failed");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void custodyResultWithoutWalletIdDoesNotCompleteOrRefundUnboundWithdrawal() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET wallet_withdrawal_id = NULL WHERE withdrawal_id = ?",
                withdrawalId);
        when(walletClient.withdrawalsByExternalReference(
                "custody-wallet-withdrawal:integration", "ETH", "USDT", 2))
                .thenReturn(List.of(Map.of("externalReference","custody-wallet-withdrawal:integration","status","CONFIRMED","asset","USDT","chain","ETH","amount","25","toAddress","0xrecipient","fundingSource","TENANT_POOL","subject","user:42")));

        reconciliationService.reconcile(repository.find(withdrawalId));

        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
        assertThat(repository.find(withdrawalId).walletWithdrawalId()).isNull();
    }

    @Test
    void idempotentTerminalWebhookAppendsAuditEvent() {
        forceStatus("COMPLETED");

        withdrawalService.handleWebhook("provider-event-terminal-1", "WITHDRAWAL.CONFIRMED", Map.of(
                "data", Map.of("withdrawalId","wallet-integration","externalReference","custody-wallet-withdrawal:integration","asset","USDT","chain","ETH","amount","25","toAddress","0xrecipient","fundingSource","TENANT_POOL","subject","user:42")));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gateway_wallet_withdrawal_events
                WHERE withdrawal_id = ? AND event_type = 'WEBHOOK_IDEMPOTENT'
                """, Integer.class, withdrawalId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT provider_event_id FROM gateway_wallet_withdrawal_events
                 WHERE withdrawal_id = ? AND event_type = 'WEBHOOK_IDEMPOTENT'
                """, String.class, withdrawalId)).isEqualTo("provider-event-terminal-1");
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
            assertThatThrownBy(() -> states.findByWalletReference(
                    "wallet-owned", "custody-wallet-withdrawal:integration"))
                    .isInstanceOf(IllegalStateException.class);

            CustodyWithdrawalModels.WithdrawalRecord bound = states.findByWalletReference(
                    "wallet-bound", "custody-wallet-withdrawal:integration");
            assertThat(bound.walletWithdrawalId()).isEqualTo("wallet-bound");
            assertThat(repository.find(withdrawalId).walletWithdrawalId()).isEqualTo("wallet-bound");
            assertThatThrownBy(() -> states.findByWalletReference(
                    "wallet-other", "custody-wallet-withdrawal:integration"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM gateway_wallet_withdrawals WHERE withdrawal_id = ?", otherId);
        }
    }

    @Test
    void stateTransitionRejectsWalletIdOwnedByAnotherWithdrawal() {
        forceStatusAndClearWallet("DEBITED");
        UUID otherId = insertWithdrawal("custody-wallet-withdrawal:other-state", "wallet-owned-state");

        try {
            assertThatThrownBy(() -> states.markSubmitted(
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
        forceStatus("SUBMITTED");

        states.markCompleted(withdrawalId, "{}", "wallet-integration");
        states.markCompleted(withdrawalId, "{}", "wallet-integration");

        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void automaticStateChangesAppendImmutableWithdrawalEvents() {
        forceStatus("DEBITED");

        states.markSubmitted(withdrawalId, "{\"id\":\"wallet-integration\"}", "wallet-integration");

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
    void databaseRejectsDirectStatusChangeWithoutLegalTransitionAudit() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE gateway_wallet_withdrawals SET status = 'COMPLETED' WHERE withdrawal_id = ?",
                withdrawalId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("status transition");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void eventInsertFailureRollsBackWithdrawalState() {
        forceStatus("DEBITED");
        rejectEventInserts();
        try {
            assertThatThrownBy(() -> states.markSubmitted(
                    withdrawalId, "{\"id\":\"wallet-integration\"}", "wallet-integration"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repository.find(withdrawalId).status()).isEqualTo("DEBITED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM gateway_wallet_withdrawal_events WHERE withdrawal_id = ?
                    """, Integer.class, withdrawalId)).isZero();
        } finally {
            allowEventInserts();
        }
    }

    @Test
    void adminStateAndActionAuditRollbackTogetherWhenAuditInsertFails() {
        forceStatus("PENDING_APPROVAL");

        assertThatThrownBy(() -> states.approve(withdrawalId, 99L, "admin", "manual approval"))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void retryCannotBypassPendingApproval() {
        forceStatus("PENDING_APPROVAL");

        assertThatThrownBy(() -> states.recordAdminRetry(withdrawalId, 99L, "admin", "manual retry"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void adminActionAuditIsImmutable() {
        jdbcTemplate.update("INSERT INTO gateway_users (user_id) VALUES (99) ON CONFLICT DO NOTHING");
        UUID actionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawal_actions (
                    action_id, withdrawal_id, admin_user_id, admin_username, action, reason
                ) VALUES (?, ?, 99, 'admin', 'RETRY', 'manual retry')
                """, actionId, withdrawalId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE gateway_wallet_withdrawal_actions SET reason = 'tampered' WHERE action_id = ?", actionId))
                .hasMessageContaining("gateway wallet withdrawal actions are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM gateway_wallet_withdrawal_actions WHERE action_id = ?", actionId))
                .hasMessageContaining("gateway wallet withdrawal actions are immutable");
    }

    @Test
    void onlyNetAmountConfirmsAndPlatformIncomeIsRecognizedOnce() {
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET fee_amount='1',fee_units=1000000,received_amount='24',received_units=24000000 WHERE withdrawal_id=?",withdrawalId);
        var wrong = new java.util.HashMap<String,Object>(walletRow("CONFIRMED"));
        wrong.put("withdrawalId",wrong.get("id"));
        assertThatThrownBy(() -> withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",wrong)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
        wrong.put("amount","24");
        withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",wrong));
        withdrawalService.handleWebhook("WITHDRAWAL.CONFIRMED",Map.of("data",wrong));
        assertThat(repository.find(withdrawalId).status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT fee_units FROM gateway_wallet_withdrawal_fees WHERE withdrawal_id=?",Long.class,withdrawalId)).isEqualTo(1_000_000L);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE gateway_wallet_withdrawal_fees SET fee_units=0 WHERE withdrawal_id=?",withdrawalId))
                .hasMessageContaining("fees are immutable");
    }

    @Test
    void confirmationAndIncomeRollBackTogetherWhenAuditCannotCommit() {
        forceStatus("SUBMITTED");
        rejectEventInserts();
        try {
            assertThatThrownBy(() -> states.markCompleted(withdrawalId,"{}","wallet-integration"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM gateway_wallet_withdrawal_fees WHERE withdrawal_id=?",Integer.class,withdrawalId)).isZero();
        } finally { allowEventInserts(); }
    }

    @Test
    void conflictingIncomeCannotBeSilentlyAccepted() {
        forceStatus("SUBMITTED");
        jdbcTemplate.update("INSERT INTO gateway_wallet_withdrawal_fees(withdrawal_id,user_id,asset_symbol,fee_units) VALUES (?,42,'USDT',1)",withdrawalId);
        assertThatThrownBy(() -> states.markCompleted(withdrawalId,"{}","wallet-integration"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("income entry");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void rawHttpErrorNeverAuthorizesPositiveCoreAdjustment() {
        var refunds=applicationContext.getBean(CustodyWithdrawalRefundService.class);
        assertThatThrownBy(() -> refunds.refund(repository.find(withdrawalId),"refund","invalid","{\"status\":409}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("receipt");
        Mockito.verifyNoInteractions(spotAccountClient);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("FAILED_PENDING");
    }

    @Test
    void lostRefundAcknowledgmentRetainsCommittedProofAndOriginalCoreReference() {
        var refunds=applicationContext.getBean(CustodyWithdrawalRefundService.class);
        var applied=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var credited=new java.util.concurrent.atomic.AtomicLong();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var persisted=repository.find(withdrawalId);
            assertThat(persisted.status()).isEqualTo("REFUND_PENDING");
            assertThat(persisted.walletResponse()).contains("PRINCIPAL_RELEASED","user:42");
            String reference=call.getArgument(3);
            if(applied.add(reference)) credited.addAndGet(call.getArgument(2,Long.class));
            if(calls.getAndIncrement()==0) throw new com.surprising.gateway.provider.exception.SpotAccountUnknownException("ack lost",null);
            return null;
        }).when(spotAccountClient).adjustBalance(Mockito.eq(42L),Mockito.eq("USDT"),Mockito.eq(25_000_000L),Mockito.anyString(),Mockito.anyString());
        String evidence=new ObjectMapper().writeValueAsString(Map.of("kind","PRINCIPAL_RELEASED","receipt",walletRow("FAILED")));
        assertThatThrownBy(() -> refunds.refund(repository.find(withdrawalId),"refund","failed",evidence))
                .isInstanceOf(com.surprising.gateway.provider.exception.SpotAccountUnknownException.class);
        assertThat(repository.find(withdrawalId).status()).isEqualTo("REFUND_PENDING");
        refunds.refund(repository.find(withdrawalId),"refund","resume");
        assertThat(repository.find(withdrawalId).status()).isEqualTo("REFUNDED");
        assertThat(credited.get()).isEqualTo(25_000_000L);
        assertThat(applied).containsExactly("custody-wallet-withdrawal:integration:refund");
        refunds.refund(repository.find(withdrawalId),"refund","duplicate");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM gateway_wallet_withdrawal_fees WHERE withdrawal_id=?",Integer.class,withdrawalId)).isZero();
    }

    @Test
    void recoveryLeasesAreBoundedDisjointAndDoNotStarveOtherIntents() throws Exception {
        for(int i=0;i<8;i++) states.createOrGet(intent("lease-"+i,"hash-"+i,new BigDecimal("1000000")));
        jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET updated_at=now()-interval '1 hour',reconcile_after=now()-interval '1 hour' WHERE user_id in (42,43)");
        var grace=java.time.Duration.ofSeconds(1);var lease=java.time.Duration.ofMinutes(1);
        Future<List<CustodyWithdrawalModels.WithdrawalRecord>> first=executor.submit(() -> repository.claimRecoverable(grace,lease,3));
        Future<List<CustodyWithdrawalModels.WithdrawalRecord>> second=executor.submit(() -> repository.claimRecoverable(grace,lease,3));
        var a=first.get(5,TimeUnit.SECONDS);var b=second.get(5,TimeUnit.SECONDS);
        assertThat(a).hasSize(3);assertThat(b).hasSize(3);
        var ids=new java.util.HashSet<UUID>();
        a.forEach(row -> assertThat(ids.add(row.withdrawalId())).isTrue());
        b.forEach(row -> assertThat(ids.add(row.withdrawalId())).isTrue());
        repository.claimRecoverable(grace,lease,3).forEach(row -> assertThat(ids.add(row.withdrawalId())).isTrue());
        assertThat(ids).hasSize(9);
        assertThat(repository.claimRecoverable(grace,lease,3)).isEmpty();
    }

    @Test
    void aConfigurationChangeBeforeIntentCommitRejectsTheOldQuotationWithoutSpendingQuota() {
        com.surprising.asset.service.AssetConfigurationService config=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                applicationContext.getBean(com.surprising.asset.service.AssetConfigurationService.class));
        when(config.lockWithdrawalQuote("USDT","ETH","25")).thenReturn(new com.surprising.asset.service.AssetConfigurationService.WithdrawalQuote("USDT","ETH","25",25_000_000L,"1",1_000_000L,"24",24_000_000L,1,1L));
        assertThatThrownBy(()->states.createOrGet(intent("stale-quote","hash",new BigDecimal("50"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quote changed");
        assertThat(repository.findByUserAndIdempotency(43L,"stale-quote")).isNull();
        assertThat(repository.dailyTotal(43L)).isEqualByComparingTo("0");
        Mockito.verifyNoInteractions(spotAccountClient,walletClient);
    }

    private void rejectEventInserts() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_events_reject_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test audit insert failure';
                END;
                $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER gateway_wallet_withdrawal_events_reject_insert_trigger "
                + "BEFORE INSERT ON gateway_wallet_withdrawal_events FOR EACH ROW "
                + "EXECUTE FUNCTION gateway_wallet_withdrawal_events_reject_insert()");
    }

    private void allowEventInserts() {
        jdbcTemplate.execute("DROP TRIGGER gateway_wallet_withdrawal_events_reject_insert_trigger "
                + "ON gateway_wallet_withdrawal_events");
        jdbcTemplate.execute("DROP FUNCTION gateway_wallet_withdrawal_events_reject_insert()");
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        String url = System.getenv("SURPRISING_WITHDRAWAL_IT_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/surprising_exchange_test_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("withdrawal integration tests require an isolated local test database");
        }
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_USER", ""));
        dataSource.setPassword(System.getenv().getOrDefault("SURPRISING_WITHDRAWAL_IT_DATABASE_PASSWORD", ""));
        return dataSource;
    }

    private void createSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_users (user_id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawals ("
                + "withdrawal_id UUID PRIMARY KEY, user_id BIGINT NOT NULL, idempotency_key TEXT NOT NULL, "
                + "request_sha256 TEXT NOT NULL, chain TEXT NOT NULL, asset_symbol TEXT NOT NULL, "
                + "custody_address_id UUID, funding_source TEXT NOT NULL, to_address TEXT NOT NULL, amount TEXT NOT NULL, "
                + "amount_units BIGINT NOT NULL, fee_amount TEXT NOT NULL, fee_units BIGINT NOT NULL, received_amount TEXT NOT NULL,received_units BIGINT NOT NULL,network_id INTEGER NOT NULL,network_revision BIGINT NOT NULL, usdt_value NUMERIC(38,18) NOT NULL, external_reference TEXT NOT NULL, "
                + "spot_debit_reference TEXT NOT NULL, request_payload JSONB NOT NULL, status TEXT NOT NULL, "
                + "wallet_response JSONB, wallet_withdrawal_id TEXT, error_code TEXT, error_message TEXT, "
                + "reconcile_after TIMESTAMPTZ NOT NULL DEFAULT now(),created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "submitted_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, admin_user_id BIGINT, "
                + "admin_username TEXT, admin_reason TEXT, "
                + "CONSTRAINT gateway_wallet_withdrawals_wallet_id_uq UNIQUE (wallet_withdrawal_id), "
                + "CONSTRAINT gateway_wallet_withdrawal_idempotency_uq UNIQUE (user_id, idempotency_key))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_fees (withdrawal_id UUID PRIMARY KEY REFERENCES gateway_wallet_withdrawals,user_id BIGINT NOT NULL,asset_symbol TEXT NOT NULL,fee_units BIGINT NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_fees_immutable_trigger ON gateway_wallet_withdrawal_fees");
        jdbcTemplate.execute("""
CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_fees_immutable_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'gateway wallet withdrawal fees are immutable';
END;
$$;
                """);
        jdbcTemplate.execute("""
CREATE TRIGGER gateway_wallet_withdrawal_fees_immutable_trigger
BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_fees
FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_fees_immutable_guard();
                """);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_actions ("
                + "action_id UUID PRIMARY KEY, withdrawal_id UUID NOT NULL REFERENCES gateway_wallet_withdrawals, "
                + "admin_user_id BIGINT NOT NULL REFERENCES gateway_users, admin_username TEXT NOT NULL, "
                + "action TEXT NOT NULL, reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_actions_immutable_guard()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'gateway wallet withdrawal actions are immutable';
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_actions_immutable_trigger "
                + "ON gateway_wallet_withdrawal_actions");
        jdbcTemplate.execute("CREATE TRIGGER gateway_wallet_withdrawal_actions_immutable_trigger "
                + "BEFORE UPDATE OR DELETE ON gateway_wallet_withdrawal_actions FOR EACH ROW "
                + "EXECUTE FUNCTION gateway_wallet_withdrawal_actions_immutable_guard()");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawal_events ("
                + "event_id UUID PRIMARY KEY, withdrawal_id UUID NOT NULL REFERENCES gateway_wallet_withdrawals, "
                + "event_type TEXT NOT NULL, source TEXT NOT NULL, from_status TEXT, to_status TEXT, "
                + "wallet_withdrawal_id TEXT, provider_event_id TEXT, "
                + "payload JSONB NOT NULL DEFAULT '{}'::jsonb, reason TEXT, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawal_events "
                + "ADD COLUMN IF NOT EXISTS provider_event_id TEXT");
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
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION gateway_wallet_withdrawal_status_guard()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.status <> NEW.status AND NOT (
                        (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('PROCESSING', 'REJECTED'))
                        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('DEBIT_UNKNOWN', 'DEBITED', 'REJECTED'))
                        OR (OLD.status = 'DEBIT_UNKNOWN' AND NEW.status IN ('DEBITED','REJECTED'))
                        OR (OLD.status = 'DEBITED' AND NEW.status IN ('SUBMITTED', 'BROADCAST_UNKNOWN', 'COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
                        OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
                        OR (OLD.status = 'BROADCAST_UNKNOWN' AND NEW.status IN ('SUBMITTED', 'COMPLETED', 'FAILED_PENDING', 'REFUND_PENDING', 'REFUNDED'))
                        OR (OLD.status = 'FAILED_PENDING' AND NEW.status IN ('COMPLETED', 'REFUND_PENDING', 'REFUNDED'))
                        OR (OLD.status = 'REFUND_PENDING' AND NEW.status = 'REFUNDED')
                    ) THEN
                        RAISE EXCEPTION 'illegal gateway wallet withdrawal status transition: % -> %', OLD.status, NEW.status;
                    END IF;
                    IF NOT EXISTS (
                        SELECT 1 FROM gateway_wallet_withdrawal_events
                         WHERE withdrawal_id = NEW.withdrawal_id
                           AND from_status IS NOT DISTINCT FROM OLD.status
                           AND to_status IS NOT DISTINCT FROM NEW.status
                           AND created_at >= transaction_timestamp()
                    ) THEN
                        RAISE EXCEPTION 'gateway wallet withdrawal status transition is missing an immutable audit event';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS gateway_wallet_withdrawal_status_guard_trigger "
                + "ON gateway_wallet_withdrawals");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER gateway_wallet_withdrawal_status_guard_trigger "
                + "AFTER UPDATE OF status ON gateway_wallet_withdrawals DEFERRABLE INITIALLY DEFERRED "
                + "FOR EACH ROW EXECUTE FUNCTION gateway_wallet_withdrawal_status_guard()");
    }

    private void forceStatus(String status) {
        jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawals "
                + "DISABLE TRIGGER gateway_wallet_withdrawal_status_guard_trigger");
        try {
            jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = ? WHERE withdrawal_id = ?",
                    status, withdrawalId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawals "
                    + "ENABLE TRIGGER gateway_wallet_withdrawal_status_guard_trigger");
        }
    }

    private void forceStatusAndClearWallet(String status) {
        jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawals "
                + "DISABLE TRIGGER gateway_wallet_withdrawal_status_guard_trigger");
        try {
            jdbcTemplate.update("UPDATE gateway_wallet_withdrawals SET status = ?, wallet_withdrawal_id = NULL "
                    + "WHERE withdrawal_id = ?", status, withdrawalId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE gateway_wallet_withdrawals "
                    + "ENABLE TRIGGER gateway_wallet_withdrawal_status_guard_trigger");
        }
    }

    private UUID insertFailurePendingWithdrawal() {
        return insertWithdrawal("custody-wallet-withdrawal:integration", "wallet-integration");
    }

    private UUID insertWithdrawal(String externalReference, String walletWithdrawalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawals (
                    withdrawal_id, user_id, idempotency_key, request_sha256, chain, asset_symbol,
                    custody_address_id, funding_source, to_address, amount, amount_units, usdt_value, external_reference,
                    spot_debit_reference, request_payload, status, wallet_withdrawal_id,fee_amount,fee_units,received_amount,received_units,network_id,network_revision
                ) VALUES (?, 42, ?, ?, 'ETH', 'USDT', null, 'TENANT_POOL', '0xrecipient', '25',
                          25000000, 25, ?, ?, '{}'::jsonb, 'FAILED_PENDING', ?, '0',0,'25',25000000,1,0)
                """, id, externalReference + ":key", "hash-" + externalReference,
                externalReference, externalReference, walletWithdrawalId);
        return id;
    }

    private Map<String, Object> walletRow(String status) {
        return walletRow(status, "wallet-integration");
    }

    private Map<String, Object> walletRow(String status, String walletWithdrawalId) {
        return Map.of("id",walletWithdrawalId,"externalReference","custody-wallet-withdrawal:integration","status",status,"asset","USDT","chain","ETH","amount","25","toAddress","0xrecipient","subject","user:42","refundable",true,"fundingSource","TENANT_POOL");
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
