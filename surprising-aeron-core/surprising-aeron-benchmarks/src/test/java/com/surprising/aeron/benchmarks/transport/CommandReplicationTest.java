package com.surprising.aeron.benchmarks.transport;

import com.surprising.aeron.protocol.*;
import io.aeron.cluster.service.Cluster;
import java.nio.ByteOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CommandReplicationTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void productionEncodedBodyAndReplicatedSequenceAreChecked(int batch) {
        byte[] bytes = CommandReplicationLoad.command(batch);
        CoreMessage command = CoreMessageCodec.decode(bytes);
        assertThat(command.header().messageType()).isEqualTo(
                batch == 1 ? CoreMessageType.PLACE_ORDER : CoreMessageType.PLACE_ORDER_BATCH);
        assertThat(command.payloadLength()).isGreaterThan(0);
        var cluster = (Cluster) java.lang.reflect.Proxy.newProxyInstance(
                Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class}, (proxy, method, args) -> {
                    if (method.getName().equals("role")) return Cluster.Role.FOLLOWER;
                    throw new UnsupportedOperationException(method.getName());
                });
        var service = new CommandReplicationService();
        service.onStart(cluster, null);
        var buffer = new UnsafeBuffer(bytes);
        service.onSessionMessage(null, 0, buffer, 0, bytes.length, null);
        assertThatThrownBy(() -> service.onSessionMessage(null, 0, buffer, 0, bytes.length, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sequence gap");
        buffer.putLong(40, 2, ByteOrder.LITTLE_ENDIAN);
        service.onSessionMessage(null, 0, buffer, 0, bytes.length, null);
        buffer.putInt(72, 0, ByteOrder.LITTLE_ENDIAN);
        assertThatThrownBy(() -> service.onSessionMessage(null, 0, buffer, 0, bytes.length, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid diagnostic command");
    }
}
