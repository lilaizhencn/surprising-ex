package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SectionedSnapshotBudgetTest {
    /** 使用真实冻结状态和 Matcher 模块，验证剩余预算在嵌套编码前拒绝。 */
    @Test
    void writerPassesRemainingBudgetToMatcherBeforeEncoding() throws Exception {
        try (var core = new TradingCoreRuntime(ProductLine.SPOT)) {
            core.activate();
            var accounts = core.runtimeState();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!accounts.tryAcquireOwnerLaneAccess()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("lane handoff timed out");
                Thread.onSpinWait();
            }
            try {
                var state = core.tradingState();
                var fees = core.feePolicies();
                var transfers = core.pendingTransfers();
                long hash = TradingCoreRuntime.canonicalBusinessStateHash(state.businessStateHash(), fees, transfers);
                var matcher = core.snapshots.captureMatcherSnapshot(19, 0, hash, state, List.of())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                var image = SectionedCoreSnapshotWriter.capture(core, matcher, 19, 0, 0, 0,
                        state, fees, transfers, hash);
                byte[] encoded = SectionedCoreSnapshotWriter.encode(image, core.configuration().snapshot().maxBytes())
                        .toByteArray();
                ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
                int sectionCount = buffer.getInt(8);
                int prefixBytes = 0;
                buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
                for (int section = 0; section < 3; section++) {
                    buffer.getInt();
                    int length = buffer.getInt();
                    prefixBytes += length;
                    buffer.position(buffer.position() + length);
                }
                int budget = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                        + sectionCount * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                        + SectionedCoreSnapshotCodec.FOOTER_LENGTH + prefixBytes + 64;
                assertThatThrownBy(() -> SectionedCoreSnapshotWriter.encode(image, budget))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("matcher snapshot exceeds maximum size");
            } finally { accounts.releaseOwnerLaneAccess(); }
        }
    }
}
