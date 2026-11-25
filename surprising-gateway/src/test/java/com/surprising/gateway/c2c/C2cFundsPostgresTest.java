package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.service.C2cFundService;

import com.surprising.gateway.c2c.service.C2cMarketService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.service.C2cFundService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cFundsPostgresTest {
    private JdbcTemplate jdbc;
    private C2cDatabaseFixture database;
    private C2cFundsRepository repository;
    private AccountCommandGateway accounts;
    private GatewayProductServices products;
    private C2cFundService funds;
    @BeforeEach void setup() throws Exception {
        database = new C2cDatabaseFixture();
        jdbc = database.jdbc;
        repository = new C2cFundsRepository(jdbc);
        accounts = mock(AccountCommandGateway.class);
        products = mock(GatewayProductServices.class);
        when(products.service(ProductLine.SPOT, AccountCommandGateway.class)).thenReturn(accounts);
        funds = new C2cFundService(repository, products, C2cDatabaseFixture.markets(jdbc));
    }
    @AfterEach void cleanup() { if (database != null) database.close(); }
    private C2cFundsRepository.Intent reserve() {
        return repository.createOrGet("ORDER", UUID.randomUUID(), "ORDER_RESERVE", 11, 1, "USDT", 9007199254740993L);
    }

    @Test void lostDebitReplyAcrossRestartReusesTheIdenticalCommandAndNeverRefunds() {
        var intent = reserve();
        when(accounts.transferOut(any())).thenThrow(new IllegalStateException("RESULT_UNKNOWN")).thenReturn((ProductBalanceResponse) null);
        assertThat(funds.resume(intent.id()).status()).isEqualTo("PREPARED");
        verify(accounts, never()).transferIn(any());
        var restarted = new C2cFundService(new C2cFundsRepository(jdbc), products, C2cDatabaseFixture.markets(jdbc));
        assertThat(restarted.resume(intent.id()).status()).isEqualTo("APPLIED");
        var commands = org.mockito.ArgumentCaptor.forClass(ProductTransferOperationRequest.class);
        verify(accounts, times(2)).transferOut(commands.capture());
        assertThat(commands.getAllValues().getFirst()).isEqualTo(commands.getAllValues().getLast());
        assertThat(commands.getValue().amountUnits()).isEqualTo(9007199254740993L);
        assertThat(commands.getValue().recipientUserId()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents", Integer.class)).isOne();
        restarted.resume(intent.id());
        verify(accounts, times(1)).transferIn(any());
        verify(accounts, times(1)).completeTransfer(any());
    }

    @Test void creditAndCompletionFailuresNeverRepeatTheSourceDebit() {
        var intent = reserve();
        when(accounts.transferIn(any())).thenThrow(new IllegalStateException("RESULT_UNKNOWN")).thenReturn((ProductBalanceResponse) null);
        org.mockito.Mockito.doThrow(new IllegalStateException("RESULT_UNKNOWN")).doNothing().when(accounts).completeTransfer(any());
        assertThat(funds.resume(intent.id()).status()).isEqualTo("DEBITED");
        assertThat(funds.resume(intent.id()).status()).isEqualTo("CREDITED");
        assertThat(funds.resume(intent.id()).status()).isEqualTo("APPLIED");
        verify(accounts, times(1)).transferOut(any());
        var credits = org.mockito.ArgumentCaptor.forClass(ProductTransferOperationRequest.class);
        verify(accounts, times(2)).transferIn(credits.capture());
        assertThat(credits.getAllValues().getFirst()).isEqualTo(credits.getAllValues().getLast());
    }

    @Test void explicitSourceRejectionCreatesNoCounterpartyCredit() {
        var intent = reserve();
        when(accounts.transferOut(any())).thenThrow(new AccountCommandRejectedException("INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE"));
        assertThat(funds.resume(intent.id()).status()).isEqualTo("REJECTED");
        funds.resume(intent.id());
        verify(accounts, times(1)).transferOut(any());
        verify(accounts, never()).transferIn(any());
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
