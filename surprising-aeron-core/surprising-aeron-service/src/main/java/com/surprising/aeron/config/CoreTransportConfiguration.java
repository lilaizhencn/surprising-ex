package com.surprising.aeron.config;

import io.aeron.ChannelUri;
import io.aeron.driver.ThreadingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 启动时一次绑定并校验的 Aeron 网络、会话和驱动线程配置。 */
@ConfigurationProperties(prefix = "surprising.aeron")
public record CoreTransportConfiguration(
        @DefaultValue("64") int maxConcurrentSessions,
        @DefaultValue Driver core,
        @DefaultValue Service service,
        @DefaultValue("aeron:udp?term-length=16m") String clusterIngressChannel,
        @DefaultValue("aeron:ipc?term-length=1m") String archiveLocalControlChannel) {

    public CoreTransportConfiguration {
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 1_024) {
            throw new IllegalArgumentException("surprising.aeron.max-concurrent-sessions must be in [1,1024]");
        }
        if (core == null || service == null) {
            throw new IllegalArgumentException("Aeron driver and service configuration are required");
        }
        clusterIngressChannel = requireChannel("cluster-ingress-channel", clusterIngressChannel);
        archiveLocalControlChannel = requireChannel("archive-local-control-channel", archiveLocalControlChannel);
    }

    private static String requireChannel(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("surprising.aeron." + property + " must be an Aeron channel URI");
        }
        String channel = value.trim();
        try {
            ChannelUri.parse(channel);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "surprising.aeron." + property + " must be an Aeron channel URI: " + channel, exception);
        }
        return channel;
    }

    /** 未显式配置时按可用 CPU 数量选择驱动模式。 */
    public record Driver(ThreadingMode threadingMode) {
        public Driver {
            if (threadingMode == null) {
                threadingMode = Runtime.getRuntime().availableProcessors() >= 12
                        ? ThreadingMode.DEDICATED : ThreadingMode.SHARED_NETWORK;
            }
        }
    }

    /** null 表示保留 Aeron 自己的默认服务等待策略。 */
    public record Service(ServiceIdleStrategy idleStrategy) {}

    public enum ServiceIdleStrategy { BACKOFF, YIELDING }
}
