package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreNativeSnapshotProductLineTest {

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restoresPairedNativeSnapshotForEveryProductLine(ProductLine productLine) {
        byte[] snapshot;
        int bookHash;
        try (TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            snapshot = state.snapshot(101);
            bookHash = state.matchingStateHashAsync().join();
        }

        CoreSnapshotManifest manifest = TradingCoreRuntime.inspectSnapshot(productLine, snapshot);
        try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
            assertThat(manifest.productLine()).isEqualTo(productLine);
            assertThat(manifest.coreShardId()).isEqualTo("default");
            assertThat(manifest.routeVersion()).isEqualTo(3);
            assertThat(restored.productLine()).isEqualTo(productLine);
            assertThat(restored.matchingStateHashAsync().join()).isEqualTo(bookHash);
        }
    }
}
