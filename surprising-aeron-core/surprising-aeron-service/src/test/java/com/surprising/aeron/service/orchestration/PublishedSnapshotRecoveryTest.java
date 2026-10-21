package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.FundsStateHash;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 固定的已发布格式样本，不在测试中调用当前写入器生成历史输入。 */
class PublishedSnapshotRecoveryTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restoresCurrentFixedSnapshotAndRetainsFundsCommandIdempotency(ProductLine product) throws Exception {
        byte[] snapshot = resource(product, ".snapshot");
        String[] expected = new String(resource(product, ".expected"), StandardCharsets.UTF_8).split("\\n");
        long hash = Long.parseUnsignedLong(expected[0], 16);
        long fundsHash = Long.parseUnsignedLong(expected[1], 16);
        var duplicate = CoreMessageCodec.decode(resource(product, ".command"));
        try (var restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
            assertThat(restored.stateHash()).isEqualTo(hash);
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            assertThat(restored.apply(duplicate).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(restored.stateHash()).isEqualTo(hash);
            byte[] changed = duplicate.payload();
            changed[changed.length - 1] ^= 1;
            var conflicting = new CoreMessage(duplicate.header(), changed);
            assertThat(restored.apply(conflicting).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(restored.stateHash()).isEqualTo(hash);
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            // A second snapshot preserves the same financial state and command idempotency.
            try (var upgraded = TradingCoreRuntime.fromSnapshot(product, restored.snapshot())) {
                assertThat(upgraded.stateHash()).isEqualTo(hash);
                assertThat(FundsStateHash.compute(upgraded.snapshotTradingState())).isEqualTo(fundsHash);
                assertThat(upgraded.apply(duplicate).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(upgraded.stateHash()).isEqualTo(hash);
                assertThat(upgraded.apply(conflicting).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(upgraded.stateHash()).isEqualTo(hash);
            }
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectsRetiredProbeContainingSnapshotSchema(ProductLine product) throws Exception {
        byte[] old = read("/recovery/v26-v36-274495ae/" + product.name() + ".snapshot");
        assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(product, old))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported snapshot version: 26");
    }

    @org.junit.jupiter.api.Test
    void rejectsOldTransferSnapshotsAtTheSchemaBoundary() throws Exception {
        for (String name : new String[]{"published-v1-transfer", "published-v1-multi"}) {
            byte[] old = read("/recovery/a8df45ae/" + name + ".snapshot");
            assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, old))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported snapshot version: 26");
        }
    }

    private static byte[] read(String name) throws IOException {
        try (var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }

    private static byte[] resource(ProductLine product, String suffix) throws IOException {
        String name = "/recovery/v27-probe-removal-20261018/" + product.name() + suffix;
        try (var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }

}
