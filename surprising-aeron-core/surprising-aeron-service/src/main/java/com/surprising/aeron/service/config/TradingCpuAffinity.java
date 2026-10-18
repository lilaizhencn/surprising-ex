package com.surprising.aeron.service.config;

import com.surprising.aeron.service.state.LaneTopology;
import java.util.BitSet;
import java.util.Locale;
import net.openhft.affinity.Affinity;

/** Immutable node configuration; native masks are changed only on the four trading threads. */
public final class TradingCpuAffinity {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingCpuAffinity.class);
    private static final TradingCpuAffinity DISABLED = new TradingCpuAffinity(false, -1, -1, -1, -1);
    private static final Binding UNBOUND = new Binding(null);
    private final boolean enabled;
    private final int[] cpus;

    public TradingCpuAffinity(boolean enabled, int owner, int matcher, int lane0, int lane1) {
        this.enabled = enabled;
        this.cpus = new int[]{owner, matcher, lane0, lane1};
    }

    public static TradingCpuAffinity disabled() { return DISABLED; }

    /** Validate before starting any trading worker; disabled/macOS never touch native affinity. */
    public void validateConfiguration(LaneTopology topology) {
        if (!enabled) return;
        if (isMac()) {
            LOG.info("Trading CPU affinity skipped on macOS");
            return;
        }
        requireLinux();
        validateCpuIds(cpus);
        validateAllowedCpus(cpus, Affinity.getAffinity());
        if (topology.matchingEngineCount() != 1 || topology.accountLaneCount() != 2)
            throw new IllegalArgumentException("CPU affinity requires one Matcher and two Account Lanes");
    }

    public Binding owner() { return bind(0, "Owner"); }

    public Binding matcher(int shard) {
        if (enabled && !isMac() && shard != 0)
            throw new IllegalArgumentException("CPU affinity supports only Matcher 0");
        return bind(1, "Matcher " + shard);
    }

    public Binding accountLane(int lane) {
        if (enabled && !isMac() && (lane < 0 || lane > 1))
            throw new IllegalArgumentException("CPU affinity supports only Account Lanes 0 and 1");
        return bind(lane + 2, "Account Lane " + lane);
    }

    private Binding bind(int index, String role) {
        if (!enabled || isMac()) return UNBOUND;
        requireLinux();
        validateCpuIds(cpus);
        BitSet previous = Affinity.getAffinity();
        validateAllowedCpus(cpus, previous);
        BitSet selected = new BitSet();
        selected.set(cpus[index]);
        try {
            Affinity.setAffinity(selected);
            if (!selected.equals(Affinity.getAffinity()))
                throw new IllegalStateException(role + " CPU affinity was not applied: " + selected);
        } catch (RuntimeException | Error failure) {
            try { Affinity.setAffinity(previous); }
            catch (RuntimeException | Error restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
        LOG.info("Trading CPU affinity role={} thread={} cpu={}", role, Thread.currentThread().getName(), cpus[index]);
        return new Binding(previous);
    }

    static void validateCpuIds(int[] cpus) {
        for (int i = 0; i < cpus.length; i++) {
            if (cpus[i] < 0) throw new IllegalArgumentException("trading CPU ID must be non-negative");
            for (int j = 0; j < i; j++)
                if (cpus[j] == cpus[i]) throw new IllegalArgumentException("trading CPU IDs must be distinct");
        }
    }

    static void validateAllowedCpus(int[] cpus, BitSet allowed) {
        for (int cpu : cpus)
            if (!allowed.get(cpu)) throw new IllegalArgumentException("trading CPU " + cpu + " is outside allowed mask " + allowed);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static void requireLinux() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"))
            throw new IllegalStateException("Trading CPU affinity requires Linux; macOS explicitly skips binding");
    }

    /** Thread-local native mask lease; closed on the same worker that acquired it. */
    public static final class Binding implements AutoCloseable {
        private final BitSet previous;
        private Binding(BitSet previous) { this.previous = previous; }
        @Override
        public void close() {
            if (previous != null) Affinity.setAffinity(previous);
        }
    }
}
