package com.surprising.aeron.config;

import java.util.BitSet;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.openhft.affinity.Affinity;

/**
 * 给 Owner、Matcher 0、账户 Lane 0/1 分配指定 CPU，并在工作结束时恢复线程原来的 CPU 范围。
 * 配置本身只读；真正的系统调用必须由被绑定的工作线程执行，不能由启动线程代办。
 */
public final class TradingCpuAffinity {
    /** 记录绑定是否生效及对应线程，方便核对部署配置。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingCpuAffinity.class);
    /** 共用的关闭配置；关闭时不会调用操作系统的 CPU 绑定接口。 */
    private static final TradingCpuAffinity DISABLED = new TradingCpuAffinity(false, -1, -1, -1, -1);
    /** 表示本次没有改过 CPU 范围，关闭这个凭据时也不需要恢复。 */
    private static final Binding UNBOUND = new Binding(null);
    /** 本节点是否要求启用 CPU 绑定，macOS 即便开启也会跳过。 */
    private final boolean enabled;
    /** 四个 CPU 编号，顺序固定为 Owner、Matcher 0、Lane 0、Lane 1。 */
    private final int[] cpus;

    /**
     * 固定四种线程角色的 CPU 配置；这里只保存编号，不改变调用线程。
     * @param enabled 是否要求启用绑定
     * @param owner 交易 Owner 的 CPU 编号
     * @param matcher Matcher 0 的 CPU 编号
     * @param lane0 账户 Lane 0 的 CPU 编号
     * @param lane1 账户 Lane 1 的 CPU 编号
     */
    public TradingCpuAffinity(boolean enabled, int owner, int matcher, int lane0, int lane1) {
        this.enabled = enabled;
        this.cpus = new int[]{owner, matcher, lane0, lane1};
    }

    /** 返回不做任何 CPU 绑定的共用配置，供未开启绑定的节点和测试使用。 */
    public static TradingCpuAffinity disabled() { return DISABLED; }

    /**
     * 在启动工作线程前检查平台、CPU 编号和线程数量，防止运行到一半才发现配置不可用。
     * 关闭绑定或运行在 macOS 时直接返回，不调用本地绑定库。
     * @param topology 本节点的 Matcher 数量与账户 Lane 数量
     */
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

    /**
     * 由 Matcher 工作线程调用；开启绑定时只支持 Matcher 0。
     * @param shard 当前 Matcher 的分片编号
     * @return 关闭时能恢复原 CPU 范围的凭据；未执行绑定时返回空操作凭据
     */
    public Binding matcher(int shard) {
        if (enabled && !isMac() && shard != 0)
            throw new IllegalArgumentException("CPU affinity supports only Matcher 0");
        return bind(1, "Matcher " + shard);
    }

    /**
     * 由账户工作线程调用；开启绑定时只支持两个固定账户 Lane。
     * @param lane 当前账户 Lane 编号；实际启用绑定时只接受 0 或 1，跳过绑定时不限制
     * @return 当前线程的 CPU 恢复凭据
     */
    public Binding accountLane(int lane) {
        if (enabled && !isMac() && (lane < 0 || lane > 1))
            throw new IllegalArgumentException("CPU affinity supports only Account Lanes 0 and 1");
        return bind(lane + 2, "Account Lane " + lane);
    }

    /**
     * 保存当前 CPU 范围，再把当前线程限制到一个 CPU；失败时尽力恢复，不能吞掉原异常。
     * @param index 角色在四项 CPU 配置中的下标
     * @param role 用于日志和错误提示的线程角色名称
     */
    private Binding bind(int index, String role) {
        if (!enabled || isMac()) return UNBOUND;
        requireLinux();
        validateCpuIds(cpus);
        // 保存调用线程原先允许使用的 CPU，成功结束或绑定失败都要用它恢复。
        BitSet previous = Affinity.getAffinity();
        validateAllowedCpus(cpus, previous);
        // 新范围只包含当前角色配置的一个 CPU，不改变其他线程的范围。
        BitSet selected = new BitSet();
        selected.set(cpus[index]);
        try {
            Affinity.setAffinity(selected);
            if (!selected.equals(Affinity.getAffinity()))
                throw new IllegalStateException(role + " CPU affinity was not applied: " + selected);
        } catch (/* 保存绑定失败的原因；恢复失败只作为它的附加异常。 */ RuntimeException | Error failure) {
            try { Affinity.setAffinity(previous); }
            catch (/* 恢复原 CPU 范围时发生的错误，不能覆盖最先发生的绑定错误。 */
                    RuntimeException | Error restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
        LOG.info("Trading CPU affinity role={} thread={} cpu={}", role, Thread.currentThread().getName(), cpus[index]);
        return new Binding(previous);
    }

    /**
     * 检查四个角色都已配置 CPU，且没有两个角色使用同一个 CPU。
     * @param cpus 按角色顺序排列的操作系统 CPU 编号
     */
    static void validateCpuIds(int[] cpus) {
        for (/* 当前要检查的角色下标。 */ int i = 0; i < cpus.length; i++) {
            if (cpus[i] < 0) throw new IllegalArgumentException("trading CPU ID must be non-negative");
            for (/* 当前角色之前的下标，用来逐项检查重复 CPU。 */ int j = 0; j < i; j++)
                if (cpus[j] == cpus[i]) throw new IllegalArgumentException("trading CPU IDs must be distinct");
        }
    }

    /**
     * 检查配置的 CPU 是否在当前进程或线程被允许使用的范围里。
     * @param cpus 所有交易角色配置的 CPU 编号
     * @param allowed 操作系统当前允许使用的 CPU 集合
     */
    static void validateAllowedCpus(int[] cpus, BitSet allowed) {
        for (/* 逐个核对的角色 CPU 编号。 */ int cpu : cpus)
            if (!allowed.get(cpu)) throw new IllegalArgumentException("trading CPU " + cpu + " is outside allowed mask " + allowed);
    }

    /** 判断是否运行在 macOS；该平台明确跳过本功能。 */
    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** 拒绝在既不是 Linux、又没有走 macOS 跳过分支的平台上执行本地 CPU 绑定。 */
    private static void requireLinux() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"))
            throw new IllegalStateException("Trading CPU affinity requires Linux; macOS explicitly skips binding");
    }

    /** 一次 CPU 绑定的恢复凭据，必须由申请它的同一个工作线程关闭。 它只记住绑定前的范围，不负责启动或停止线程。 构造约定：保存当前线程结束绑定时需要恢复的范围。 参数 previous：原 CPU 范围；空操作凭据传 null */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Binding implements AutoCloseable {
        /** 绑定前的 CPU 范围；null 表示没有执行绑定，关闭时无需操作系统调用。 */
        private final BitSet previous;
        /** 在当前工作线程中恢复申请绑定之前的 CPU 范围。 */
        @Override
        public void close() {
            if (previous != null) Affinity.setAffinity(previous);
        }
    }
}
