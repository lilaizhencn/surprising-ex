package com.surprising.aeron.service.core;

import com.surprising.aeron.controller.AeronTradingClusterService;
import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖六产品线的真实 AeronTradingClusterService：Owner 只能使用复制进来的日志元数据，不能从业务线程调用 Cluster；动作边界后按输入顺序观察最新值。
 */
class OwnerLogContextTest {
    /**
     * 把 Cluster 代理限制为仅回调线程可用；启动真实服务，依序交入两条命令，再通过角色切换边界等待，断言保存最后的业务时间、位置和新角色。
     * @param product 当前参数化产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ownerUsesCopiedValuesAndUpdatesThemInInputOrderWithoutCallingCluster(ProductLine product) throws Exception {
        // 允许访问 Cluster 代理的测试回调线程
        Thread serviceThread = Thread.currentThread();
        // 只允许指定回调方法及线程访问的 Cluster 动态代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 代理调用附带的代理对象，当前处理不读取 */ proxy, /* 当前代理方法，用来限制允许读取的 Cluster 元数据 */ method, /* 代理方法实参，当前元数据处理不读取 */ args) -> {
                    if (Thread.currentThread() != serviceThread)
                        throw new AssertionError("Owner called Cluster." + method.getName());
                    return switch (method.getName()) {
                        case "role" -> Cluster.Role.LEADER;
                        case "memberId" -> 7;
                        case "time" -> 5L;
                        case "logPosition" -> 11L;
                        case "idleStrategy" -> org.agrona.concurrent.NoOpIdleStrategy.INSTANCE;
                        default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                    };
                });
        // 与真实服务配套的响应出口，最终清理
        var egress = new ClusterServiceEgress();
        egress.start(cluster);
        // 创建实际服务的生产对象
        var service = new AeronTradingClusterService(product, egress);
        try {
            service.onStart(cluster, null);
            service.enqueue(probe(product, 1), ClusterServiceEgress.NO_SESSION_ID, 70, 700, null);
            service.enqueue(probe(product, 2), ClusterServiceEgress.NO_SESSION_ID, 80, 800, null);
            // 使用既有控制动作边界等待前面输入完成，再读取已交出的上下文。
            service.onRoleChange(Cluster.Role.FOLLOWER);
            // 读取日志上下文
            var context = service.logContext();
            assertThat(Cluster.class.isAssignableFrom(context.getClass())).isFalse();
            assertThat(context.role()).isEqualTo(Cluster.Role.FOLLOWER);
            assertThat(context.timestamp()).isEqualTo(80);
            assertThat(context.position()).isEqualTo(800);
            service.checkFailure();
        } finally { service.onTerminate(cluster); egress.clear(); }
    }

    /**
     * 构造带唯一来源序号的余额调整探针，让真实输入流程更新日志上下文，无需注册币对。
     * @param product 当前参数化产品线
     * @param sequence 探针唯一来源序号，同时参与 UUID 和日志顺序
     */
    private static CoreMessage probe(ProductLine product, long sequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(45, sequence), product, CommandSource.OPERATIONS, 45, sequence, 7, 1, sequence),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
