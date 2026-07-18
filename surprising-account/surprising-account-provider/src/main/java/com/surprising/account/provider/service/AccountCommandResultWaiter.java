package com.surprising.account.provider.service;

import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountCommandRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Waits for synchronous HTTP command results without issuing one database query per request and
 * poll interval. Kafka result topics are intentionally not required for correctness.
 */
@Service
public class AccountCommandResultWaiter {

    private final AccountCommandRepository commandRepository;
    private final Map<String, WaitSlot> waiting = new ConcurrentHashMap<>();

    public AccountCommandResultWaiter(AccountCommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    public AccountCommandTerminalResult await(String commandId, Duration timeout) {
        WaitSlot slot = waiting.compute(commandId, (ignored, existing) -> {
            WaitSlot selected = existing == null ? new WaitSlot() : existing;
            selected.waiterCount.incrementAndGet();
            return selected;
        });
        try {
            commandRepository.terminalResult(commandId).ifPresent(slot.result::complete);
            return slot.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new AccountCommandTimeoutException(
                    "account command is durable but did not finish before timeout: " + commandId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AccountCommandTimeoutException(
                    "interrupted while waiting for account command " + commandId);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("failed while waiting for account command " + commandId,
                    ex.getCause());
        } finally {
            if (slot.waiterCount.decrementAndGet() == 0) {
                waiting.remove(commandId, slot);
            }
        }
    }

    @Scheduled(fixedDelayString = "${surprising.account.command-wait.poll-delay-ms:20}")
    public void completeTerminalCommands() {
        if (waiting.isEmpty()) {
            return;
        }
        Map<String, AccountCommandTerminalResult> completed =
                commandRepository.terminalResults(new ArrayList<>(waiting.keySet()));
        completed.forEach((commandId, result) -> {
            WaitSlot slot = waiting.get(commandId);
            if (slot != null) {
                slot.result.complete(result);
            }
        });
    }

    private static final class WaitSlot {
        private final CompletableFuture<AccountCommandTerminalResult> result = new CompletableFuture<>();
        private final AtomicInteger waiterCount = new AtomicInteger();
    }
}
