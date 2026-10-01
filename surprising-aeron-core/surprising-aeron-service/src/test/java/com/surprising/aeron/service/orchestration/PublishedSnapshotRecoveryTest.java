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
    void restoresPublishedSnapshotAndRetainsFundsCommandIdempotency(ProductLine product) throws Exception {
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
            // Writing the new field format must preserve the old financial state across another restart.
            try (var upgraded = TradingCoreRuntime.fromSnapshot(product, restored.snapshot())) {
                assertThat(upgraded.stateHash()).isEqualTo(hash);
                assertThat(FundsStateHash.compute(upgraded.snapshotTradingState())).isEqualTo(fundsHash);
                assertThat(upgraded.apply(duplicate).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(upgraded.stateHash()).isEqualTo(hash);
            }
        }
    }
    @org.junit.jupiter.api.Test
    void restoresActualPublishedV1PendingTransferBeforeCompletingIt() throws Exception {
        String prefix = "/recovery/a8df45ae/published-v1-transfer";
        byte[] snapshot = read(prefix + ".snapshot");
        var oldCommand = CoreMessageCodec.decode(read(prefix + ".command"));
        String[] expected = new String(read(prefix + ".expected"), StandardCharsets.UTF_8).split("\\n");
        long fundsHash = Long.parseUnsignedLong(expected[1], 16);
        // Original bytes and their original business hash are verified before the in-memory field upgrade.
        TradingCoreRuntime.inspectSnapshot(ProductLine.SPOT, snapshot);
        try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, snapshot)) {
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4750);
            var transfer = restored.pendingTransfers().get(8844L).command();
            assertThat(transfer.sourceUserId()).isEqualTo(1001);
            assertThat(transfer.targetUserId()).isEqualTo(1001);
            assertThat(restored.apply(new CoreMessage(oldCommand.header(), TradingCommandCodec.encodeTransferFunds(transfer))).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(restored.apply(oldCommand).status()).isEqualTo(ResponseStatus.DUPLICATE);
            long upgradedHash = restored.stateHash();
            try (var nextRestart = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, restored.snapshot())) {
                assertThat(nextRestart.stateHash()).isEqualTo(upgradedHash);
                assertThat(nextRestart.pendingTransfers().get(8844L).command()).isEqualTo(transfer);
                var complete = new CoreMessage(CoreMessageHeader.command(CoreMessageType.COMPLETE_TRANSFER,
                        new java.util.UUID(8844, 3), ProductLine.SPOT, CommandSource.GATEWAY, 7, 3, 1001, 1000, 3),
                        TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(8844)));
                assertThat(nextRestart.apply(complete).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(nextRestart.pendingTransfers()).isEmpty();
                assertThat(nextRestart.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4750);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void restoresPublishedMultipleFeesAndPendingTransfersInDeterministicOrder() throws Exception {
        String prefix = "/recovery/a8df45ae/published-v1-multi";
        String[] expected = new String(read(prefix + ".expected"), StandardCharsets.UTF_8).split("\\n");
        long fundsHash = Long.parseUnsignedLong(expected[1], 16);
        try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, read(prefix + ".snapshot"))) {
            assertThat(restored.feePolicies().keySet()).containsExactly(7L, 9L);
            assertThat(restored.pendingTransfers().keySet()).containsExactly(1L, 9L, 17L, 257L, 8844L);
            assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4710);
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            assertThat(restored.apply(CoreMessageCodec.decode(read(prefix + ".command"))).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            try (var next = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, restored.snapshot())) {
                assertThat(next.stateHash()).isEqualTo(restored.stateHash());
                assertThat(next.feePolicies()).isEqualTo(restored.feePolicies());
                assertThat(next.pendingTransfers()).isEqualTo(restored.pendingTransfers());
                assertThat(FundsStateHash.compute(next.snapshotTradingState())).isEqualTo(fundsHash);
            }
        }
    }

    private static byte[] read(String name) throws IOException {
        try (var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }

    private static byte[] resource(ProductLine product, String suffix) throws IOException {
        String name = "/recovery/v26-v36-274495ae/" + product.name() + suffix;
        try (var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }
}
