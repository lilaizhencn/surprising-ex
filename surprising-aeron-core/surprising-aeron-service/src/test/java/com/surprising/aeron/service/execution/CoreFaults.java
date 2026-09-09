package com.surprising.aeron.service.execution;

import com.surprising.aeron.client.RealtimeOutbox;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Corrupts real commit preconditions from the test JVM, keeping failure handling in production code. */
public final class CoreFaults {
    private static final Map<Object, Long> laneMasks = new IdentityHashMap<>();
    private static final Map<Object, Runnable> settlements = new IdentityHashMap<>();
    private static Consumer<TradingCoreRuntime> activation;

    static void laneMask(TradingCoreRuntime state, long mask) {
        requireAgent();
        laneMasks.put(state, mask);
    }

    static void afterSettlement(TradingCoreRuntime state, Runnable assertion) {
        requireAgent();
        settlements.put(state, assertion);
    }

    static void beforeActivation(Consumer<TradingCoreRuntime> observer) {
        requireAgent();
        activation = observer;
    }

    public static void observeActivation(Object candidate) {
        if (activation != null) activation.accept((TradingCoreRuntime) candidate);
    }

    public static void afterLaneContext(Object state, Object batch) {
        Long mask = laneMasks.remove(((OrderBatchExecutor) state).owner);
        if (mask != null) set(batch, "actualLaneMask", mask);
    }

    public static void afterSettlement(Object state, Object batch) {
        Runnable assertion = settlements.remove(((OrderBatchExecutor) state).owner);
        if (assertion == null) return;
        assertion.run();
        set(batch, "actualLaneMask", (long) get(batch, "actualLaneMask") ^ 1L);
    }

    static void attachRealtime(SurprisingClusteredService service, RealtimeOutbox outbox) {
        TradingCoreRuntime state = (TradingCoreRuntime) get(service, "state");
        set(service, "realtimeOutbox", outbox);
        set(service, "realtimeCapture", state.attachRealtime(outbox));
    }

    static ActivationState activationState(TradingCoreRuntime state) {
        return new ActivationState(state.activated(),
                state.activated(),
                ((com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter)
                        get(state, "matchingAdapter")).activated(),
                ((com.surprising.aeron.service.state.RuntimeCommitJournal)
                        get(state, "runtimeProjectionJournal")).activated(),
                ((CoreExportState) get(state, "exportState")).activated());
    }

    record ActivationState(boolean core, boolean runtime, boolean matcher,
                           boolean projector, boolean exportMaterializer) {
        boolean allPassive() {
            return !core && !runtime && !matcher && !projector && !exportMaterializer;
        }

        boolean allActivated() {
            return core && runtime && matcher && projector && exportMaterializer;
        }
    }

    private static void requireAgent() {
        if (!CoreFaultAgent.installed) throw new AssertionError("Surefire fault agent is required");
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
