package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class OwnerLogContextTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ownerUsesCopiedValuesAndUpdatesThemInInputOrderWithoutCallingCluster(ProductLine product) throws Exception {
        Thread serviceThread = Thread.currentThread();
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> {
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
        var egress = new ClusterServiceEgress();
        egress.start(cluster);
        var loop = new TradingOwnerLoop(product, egress);
        try {
            loop.start(cluster, null);
            loop.enqueue(probe(product, 1), ClusterServiceEgress.NO_SESSION_ID, 70, 700, null);
            loop.enqueue(probe(product, 2), ClusterServiceEgress.NO_SESSION_ID, 80, 800, null);
            // Existing control boundary waits for preceding inputs and publishes the Owner's writes.
            loop.onRoleChange(Cluster.Role.FOLLOWER, 1);
            var field = TradingOwnerLoop.class.getDeclaredField("logContext");
            field.setAccessible(true);
            var context = (OwnerLogContext) field.get(loop);
            assertThat(Cluster.class.isAssignableFrom(context.getClass())).isFalse();
            assertThat(context.role).isEqualTo(Cluster.Role.FOLLOWER);
            assertThat(context.timestamp).isEqualTo(80);
            assertThat(context.position).isEqualTo(800);
            loop.checkFailure();
        } finally { loop.terminate(); egress.clear(); }
    }

    private static CoreMessage probe(ProductLine product, long sequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(45, sequence), product, CommandSource.OPERATIONS, 45, sequence, 7, 1, sequence),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
