package com.surprising.aeron.service.state;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class AccountLaneQueryTestSupport {
    static <T> T read(TradingRuntimeState runtime, long userId, Supplier<T> query) {
        if (!runtime.accountLanesStarted) return query.get();
        var result = new CompletableFuture<T>();
        long mask = runtime.topology().accountLaneMask(userId);
        if (!runtime.dispatchAccountQuery(mask, lane -> {
            try { result.complete(runtime.readAccountLane(lane, ignored -> query.get())); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        })) throw new AssertionError("query mailbox is full");
        try { return result.get(3, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("account Lane query failed", failure); }
    }
}
