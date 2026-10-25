package com.surprising.aeron.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.StandardEnvironment;

/** 节点启动时一次绑定的执行配置；恢复沿用本节点配置，账户路由仍由快照决定。 */
@ConfigurationProperties(prefix = "surprising.aeron")
public record CoreRuntimeConfiguration(
        @DefaultValue("1024") int laneCompletionSpins,
        @DefaultValue("4096") int maxPendingMatching,
        @DefaultValue("300") int standaloneSnapshotTimeoutSeconds,
        @DefaultValue("300") int snapshotTimeoutSeconds,
        @DefaultValue("600") int ownerRoleChangeTimeoutSeconds,
        @DefaultValue("64") int ownerInputBatchSize,
        @DefaultValue("256") int ownerCommandWindow,
        @DefaultValue("4096") int decoderCacheSlots,
        @DefaultValue("256") int matcherGroupSize,
        @DefaultValue("10000") int matcherGroupNanos,
        @DefaultValue("1024") int laneInitialEntities,
        java.nio.file.Path fundsIndexDirectory,
        @DefaultValue SnapshotLimits snapshot) {

    public CoreRuntimeConfiguration {
        requireRange("lane-completion-spins", laneCompletionSpins, 0, Integer.MAX_VALUE);
        requireRange("max-pending-matching", maxPendingMatching, 1, 1 << 20);
        requireRange("standalone-snapshot-timeout-seconds", standaloneSnapshotTimeoutSeconds, 1, 1800);
        requireRange("snapshot-timeout-seconds", snapshotTimeoutSeconds, 1, 1800);
        requireRange("owner-role-change-timeout-seconds", ownerRoleChangeTimeoutSeconds, 30, 1800);
        requireRange("owner-input-batch-size", ownerInputBatchSize, 1, 256);
        requireRange("owner-command-window", ownerCommandWindow, 64, 4096);
        if (Integer.bitCount(ownerCommandWindow) != 1)
            throw new IllegalArgumentException("surprising.aeron.owner-command-window must be a power of two");
        requireRange("decoder-cache-slots", decoderCacheSlots, 1, 1 << 20);
        requireRange("matcher-group-size", matcherGroupSize, 1, Integer.MAX_VALUE);
        requireRange("matcher-group-nanos", matcherGroupNanos, 1, Integer.MAX_VALUE);
        requireRange("lane-initial-entities", laneInitialEntities, 16, 1 << 20);
        if (snapshot == null) throw new IllegalArgumentException("snapshot limits are required");
        if (fundsIndexDirectory == null)
            fundsIndexDirectory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max)
            throw new IllegalArgumentException("surprising.aeron." + name + " must be in [" + min + "," + max + "]");
    }

    /** 工具与测试的显式非 Spring 边界：只绑定系统属性和环境变量，不隐式加载 YAML。 */
    public static CoreRuntimeConfiguration standalone() {
        return Binder.get(new StandardEnvironment())
                .bindOrCreate("surprising.aeron", Bindable.of(CoreRuntimeConfiguration.class));
    }

    /** 部署限制与协议硬上限分离，恢复器和编码器使用本次绑定的值。 */
    public record SnapshotLimits(@DefaultValue("1073741824") int maxBytes) {
        public SnapshotLimits {
            requireRange("snapshot.max-bytes", maxBytes,
                    com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.MIN_MAX_SNAPSHOT_BYTES,
                    com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES);
        }
    }

}
