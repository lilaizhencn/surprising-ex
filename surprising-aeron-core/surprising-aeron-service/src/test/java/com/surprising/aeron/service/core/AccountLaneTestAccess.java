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
    public static long handoffEpoch(AccountBook accountBook) {
        return (long) field(field(accountBook, "laneLifecycle"), "handoffEpoch");
    }
    public static SettlementLaneWorker[] workers(AccountBook accountBook) {
        return (SettlementLaneWorker[]) field(field(accountBook, "laneLifecycle"), "workers");
    }
    public static void ownerAccess(AccountBook accountBook, boolean access) {
        Object lifecycle = field(accountBook, "laneLifecycle");
        try {
            var ownerAccess = lifecycle.getClass().getDeclaredField("ownerHasAccess");
            ownerAccess.setAccessible(true);
            ownerAccess.setBoolean(lifecycle, access);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    public static AtomicReference<Throwable> failure(AccountBook accountBook) {
        return (AtomicReference<Throwable>) field(field(accountBook, "laneLifecycle"), "failure");
    }
}
