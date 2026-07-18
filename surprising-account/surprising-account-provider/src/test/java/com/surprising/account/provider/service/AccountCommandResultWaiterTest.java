package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountCommandRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccountCommandResultWaiterTest {

    @Test
    void completesManyWaitersFromAuthoritativeBatchQuery() throws Exception {
        AccountCommandRepository repository = mock(AccountCommandRepository.class);
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(repository);
        CountDownLatch registered = new CountDownLatch(1);
        AccountCommandTerminalResult terminal = new AccountCommandTerminalResult(
                AccountCommandStatus.APPLIED, "{\"ok\":true}", null, null);
        when(repository.terminalResult("command-1")).thenAnswer(invocation -> {
            registered.countDown();
            return Optional.empty();
        });
        when(repository.terminalResults(anyList())).thenReturn(Map.of("command-1", terminal));

        CompletableFuture<AccountCommandTerminalResult> result = CompletableFuture.supplyAsync(
                () -> waiter.await("command-1", Duration.ofSeconds(2)));
        assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();

        waiter.completeTerminalCommands();

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(terminal);
    }

    @Test
    void returnsAlreadyTerminalCommandWithoutWaitingForScheduler() {
        AccountCommandRepository repository = mock(AccountCommandRepository.class);
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(repository);
        AccountCommandTerminalResult terminal = new AccountCommandTerminalResult(
                AccountCommandStatus.REJECTED, null, "NO_FUNDS", "insufficient balance");
        when(repository.terminalResult("command-2")).thenReturn(Optional.of(terminal));

        assertThat(waiter.await("command-2", Duration.ofSeconds(1))).isEqualTo(terminal);
    }

    @Test
    void timeoutKeepsDurableCommandRetryableByTheSameIdempotencyKey() {
        AccountCommandRepository repository = mock(AccountCommandRepository.class);
        AccountCommandResultWaiter waiter = new AccountCommandResultWaiter(repository);
        when(repository.terminalResult("command-3")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> waiter.await("command-3", Duration.ofMillis(10)))
                .isInstanceOf(AccountCommandTimeoutException.class)
                .hasMessageContaining("command-3");
    }
}
