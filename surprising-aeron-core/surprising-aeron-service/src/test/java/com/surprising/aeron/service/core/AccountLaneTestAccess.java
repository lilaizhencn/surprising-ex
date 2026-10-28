package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import java.util.concurrent.atomic.AtomicReference;

public final class AccountLaneTestAccess {
    private AccountLaneTestAccess() { }
    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static long handoffEpoch(TradingAccountRuntime runtime) {
        return (long) field(field(runtime, "laneLifecycle"), "handoffEpoch");
    }
    public static SettlementLaneWorker[] workers(TradingAccountRuntime runtime) {
        return (SettlementLaneWorker[]) field(field(runtime, "laneLifecycle"), "workers");
    }
    public static void ownerAccess(TradingAccountRuntime runtime, boolean access) {
        Object lifecycle = field(runtime, "laneLifecycle");
        try {
            var ownerAccess = lifecycle.getClass().getDeclaredField("ownerHasAccess");
            ownerAccess.setAccessible(true);
            ownerAccess.setBoolean(lifecycle, access);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    public static AtomicReference<Throwable> failure(TradingAccountRuntime runtime) {
        return (AtomicReference<Throwable>) field(field(runtime, "laneLifecycle"), "failure");
    }
}
