package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import java.util.concurrent.atomic.AtomicReference;

/** 资源故障注入和清理仅供测试，生产不暴露可变资源数组。 */
public final class AccountLaneTestAccess {
    private AccountLaneTestAccess() { }
    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static SettlementLaneWorker[] workers(TradingAccountRuntime runtime) {
        return (SettlementLaneWorker[]) field(field(runtime, "laneLifecycle"), "workers");
    }
    public static SystemLedgerLane ledger(TradingAccountRuntime runtime) {
        return (SystemLedgerLane) field(field(runtime, "laneLifecycle"), "ledger");
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
