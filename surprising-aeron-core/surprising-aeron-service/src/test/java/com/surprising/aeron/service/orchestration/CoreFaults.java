package com.surprising.aeron.service.orchestration;

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

    public static void laneMask(TradingCoreRuntime state, long mask) {
        requireAgent();
        laneMasks.put(state, mask);
    }

    public static void afterSettlement(TradingCoreRuntime state, Runnable assertion) {
        requireAgent();
        settlements.put(state, assertion);
    }

    public static void beforeActivation(Consumer<TradingCoreRuntime> observer) {
        requireAgent();
        activation = observer;
    }

    public static void observeActivation(Object candidate) {
        if (activation != null) activation.accept((TradingCoreRuntime) candidate);
    }

    public static void afterLaneContext(Object state, Object batch) {
        Long mask = laneMasks.remove(get(state, "owner"));
        if (mask != null) set(batch, "actualLaneMask", mask);
    }

    public static void afterSettlement(Object state, Object batch) {
        Runnable assertion = settlements.remove(get(state, "owner"));
        if (assertion == null) return;
        assertion.run();
        set(batch, "actualLaneMask", (long) get(batch, "actualLaneMask") ^ 1L);
    }

    public static void attachRealtime(TradingOwnerTestSupport service, RealtimeOutbox outbox) {
        service.attachRealtime(outbox);
    }

    public static ActivationState activationState(TradingCoreRuntime state) {
        return new ActivationState(state.activated(),
                state.activated(),
                ((com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter)
                        get(state, "matchingAdapter")).activated(),
                state.commits.publication.activated());
    }

    public record ActivationState(boolean core, boolean runtime, boolean matcher, boolean projector) {
        public boolean allPassive() {
            return !core && !runtime && !matcher && !projector;
        }

        public boolean allActivated() {
            return core && runtime && matcher && projector;
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
