package com.surprising.aeron.config;

import java.util.BitSet;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.openhft.affinity.Affinity;

/** 给 Owner、Matcher 0、账户 Lane 0/1 分配指定 CPU，并在工作结束时恢复线程原来的 CPU 范围。真正的系统调用必须由被绑定的工作线程执行，不能由启动线程代办。 */
public final class TradingCpuAffinity {
    /** 记录绑定是否生效及对应线程，方便核对部署配置。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingCpuAffinity.class);
    private static final TradingCpuAffinity DISABLED = new TradingCpuAffinity(false, -1, -1, -1, -1);
    /** 表示本次没有改过 CPU 范围，关闭这个凭据时也不需要恢复。 */
    private static final Binding UNBOUND = new Binding(null);
    private final boolean enabled;
    /** 四个 CPU 编号，顺序固定为 Owner、Matcher 0、Lane 0、Lane 1。 */
    private final int[] cpus;

    /** 固定四种线程角色的 CPU 配置。这里只保存编号，不改变调用线程。 */
    public TradingCpuAffinity(boolean enabled, int owner, int matcher, int lane0, int lane1) {
        this.enabled = enabled;
        this.cpus = new int[]{owner, matcher, lane0, lane1};
    }

    /** 返回不做任何 CPU 绑定的共用配置，供未开启绑定的节点和测试使用。 */
    public static TradingCpuAffinity disabled() { return DISABLED; }

    /** 在启动工作线程前检查平台、CPU 编号和线程数量，防止运行到一半才发现配置不可用。 */
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

    /** 由 Owner 自己调用，把当前线程绑定到配置的 Owner CPU，并返回恢复凭据。 */
    public Binding owner() { return bind(0, "Owner"); }

    /** 由 Matcher 工作线程调用。 */
    public Binding matcher(int shard) {
        if (enabled && !isMac() && shard != 0)
            throw new IllegalArgumentException("CPU affinity supports only Matcher 0");
        return bind(1, "Matcher " + shard);
    }

    /** 由账户工作线程调用。 */
    public Binding accountLane(int lane) {
        if (enabled && !isMac() && (lane < 0 || lane > 1))
            throw new IllegalArgumentException("CPU affinity supports only Account Lanes 0 and 1");
        return bind(lane + 2, "Account Lane " + lane);
    }

    /** 保存当前 CPU 范围，再把当前线程限制到一个 CPU。失败时尽力恢复，不能吞掉原异常。 */
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
            catch (
                    RuntimeException | Error restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
        LOG.info("Trading CPU affinity role={} thread={} cpu={}", role, Thread.currentThread().getName(), cpus[index]);
        return new Binding(previous);
    }

    /** 检查四个角色都已配置 CPU，且没有两个角色使用同一个 CPU。 */
    static void validateCpuIds(int[] cpus) {
        for (int i = 0; i < cpus.length; i++) {
            if (cpus[i] < 0) throw new IllegalArgumentException("trading CPU ID must be non-negative");
            for (int j = 0; j < i; j++)
                if (cpus[j] == cpus[i]) throw new IllegalArgumentException("trading CPU IDs must be distinct");
        }
    }

    /** 检查配置的 CPU 是否在当前进程或线程被允许使用的范围里。 */
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

    /** 一次 CPU 绑定的恢复凭据，必须由申请它的同一个工作线程关闭。它只记住绑定前的范围，不负责启动或停止线程。构造约定：保存当前线程结束绑定时需要恢复的范围。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Binding implements AutoCloseable {
        private final BitSet previous;
        /** 在当前工作线程中恢复申请绑定之前的 CPU 范围。 */
        @Override
        public void close() {
            if (previous != null) Affinity.setAffinity(previous);
        }
    }
}
