package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.service.C2cMarketService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.service.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.ProductAccountClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cFundsPostgresTest {
    private JdbcTemplate jdbc;
    private C2cDatabaseFixture database;
    private C2cFundsRepository repository;
    private ProductAccountClient accounts;
    private C2cFunds funds;
    @BeforeEach void setup() throws Exception {
        database = new C2cDatabaseFixture();
        jdbc = database.jdbc;
        repository = new C2cFundsRepository(jdbc);
        accounts = mock(ProductAccountClient.class);
        funds = new C2cFunds(repository, accounts, C2cDatabaseFixture.markets(jdbc));
    }
    @AfterEach void cleanup() { if (database != null) database.close(); }
    private C2cFundsRepository.Intent reserve() {
        return repository.createOrGet("ORDER", UUID.randomUUID(), "ORDER_RESERVE", 11, 1, "USDT", 9007199254740993L);
    }
    private static ProductAccountAdjustment applied() { return new ProductAccountAdjustment(ProductAccountAdjustment.Status.APPLIED, null); }
    private static ProductAccountAdjustment unknown() { return new ProductAccountAdjustment(ProductAccountAdjustment.Status.UNKNOWN, "RESULT_UNKNOWN"); }

    @Test void lostDebitReplyAcrossRestartReusesTheIdenticalCommandAndNeverRefunds() {
        var intent = reserve();
        when(accounts.transferOut(eq("SPOT"), any())).thenReturn(unknown(), applied());
        when(accounts.transferIn(eq("SPOT"), any())).thenReturn(applied());
        when(accounts.completeTransfer(eq("SPOT"), any())).thenReturn(applied());
        assertThat(funds.resume(intent.id()).status()).isEqualTo("PREPARED");
        verify(accounts, never()).transferIn(any(), any());
        var restarted = new C2cFunds(new C2cFundsRepository(jdbc), accounts, C2cDatabaseFixture.markets(jdbc));
        assertThat(restarted.resume(intent.id()).status()).isEqualTo("APPLIED");
        var commands = org.mockito.ArgumentCaptor.forClass(ProductTransferOperationRequest.class);
        verify(accounts, times(2)).transferOut(eq("SPOT"), commands.capture());
        assertThat(commands.getAllValues().getFirst()).isEqualTo(commands.getAllValues().getLast());
        assertThat(commands.getValue().amountUnits()).isEqualTo(9007199254740993L);
        assertThat(commands.getValue().recipientUserId()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents", Integer.class)).isOne();
        restarted.resume(intent.id());
        verify(accounts, times(1)).transferIn(any(), any());
        verify(accounts, times(1)).completeTransfer(any(), any());
    }

    @Test void creditAndCompletionFailuresNeverRepeatTheSourceDebit() {
        var intent = reserve();
        when(accounts.transferOut(any(), any())).thenReturn(applied());
        when(accounts.transferIn(any(), any())).thenReturn(unknown(), applied());
        when(accounts.completeTransfer(any(), any())).thenReturn(unknown(), applied());
        assertThat(funds.resume(intent.id()).status()).isEqualTo("DEBITED");
        assertThat(funds.resume(intent.id()).status()).isEqualTo("CREDITED");
        assertThat(funds.resume(intent.id()).status()).isEqualTo("APPLIED");
        verify(accounts, times(1)).transferOut(any(), any());
        var credits = org.mockito.ArgumentCaptor.forClass(ProductTransferOperationRequest.class);
        verify(accounts, times(2)).transferIn(eq("SPOT"), credits.capture());
        assertThat(credits.getAllValues().getFirst()).isEqualTo(credits.getAllValues().getLast());
    }

    @Test void explicitSourceRejectionCreatesNoCounterpartyCredit() {
        var intent = reserve();
        when(accounts.transferOut(any(), any())).thenReturn(new ProductAccountAdjustment(ProductAccountAdjustment.Status.REJECTED, "INSUFFICIENT_BALANCE"));
        assertThat(funds.resume(intent.id()).status()).isEqualTo("REJECTED");
        funds.resume(intent.id());
        verify(accounts, times(1)).transferOut(any(), any());
        verify(accounts, never()).transferIn(any(), any());
        assertThat(repository.due(32)).isEmpty();
    }

    @Test void internalAccountsCannotBeImportedAsLoginUsersOrReassigned() {
        long escrow = C2cDatabaseFixture.markets(jdbc).configuration().escrowUserId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO gateway_users(user_id) VALUES (?)", escrow))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE c2c_configuration SET escrow_user_id=12345"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isZero();
    }

    @Test void aCorruptIntentCannotSendEscrowMoneyToAnArbitraryReserveRecipient() {
        var wrong = repository.createOrGet("ORDER", UUID.randomUUID(), "ORDER_RESERVE", 11, 99, "USDT", 100);
        assertThatThrownBy(() -> funds.resume(wrong.id())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(accounts);
    }

    @Test void concurrentSameIntentCannotAllocateTwoTransfersOrChangeItsMoney() throws Exception {
        UUID owner = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var work = List.<Callable<C2cFundsRepository.Intent>>of(
                    () -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 1, "USDT", 100),
                    () -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 1, "USDT", 100),
                    () -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 1, "USDT", 100),
                    () -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 1, "USDT", 100));
            var ids = new java.util.HashSet<UUID>();
            for (var future : pool.invokeAll(work)) ids.add(future.get().id());
            assertThat(ids).hasSize(1);
        }
        assertThatThrownBy(() -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 12, "USDT", 100))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.createOrGet("ORDER", owner, "ORDER_RESERVE", 11, 1, "USDT", 101))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents", Integer.class)).isOne();
    }
}
