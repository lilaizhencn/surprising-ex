package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.service.orchestration.snapshot.CoreSnapshotManifest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 逐产品线验证空白运行时的 Core 与原生 Matcher 快照能够配对恢复，核对产品线、路由版本及订单簿哈希；没有注册品种、成交或资金变动场景。
 */
class CoreNativeSnapshotProductLineTest {

    /**
     * 对 ProductLine 每个枚举值保存编号一百零一的空状态快照，关闭原运行时后检查清单并恢复，要求产品线不串用、路由版本为三且订单簿哈希相同。
     * @param productLine 参数化测试当前覆盖的一条产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restoresPairedNativeSnapshotForEveryProductLine(ProductLine productLine) {
        // 从当前产品线空白运行时捕获的完整快照字节。
        byte[] snapshot;
        // 快照前后比较的订单簿 int 哈希。
        int bookHash;
        try (/* 保存快照前的原运行时，资源退出时关闭。 */ TradingCoreRuntime state = new TradingCoreRuntime(productLine)) {
            snapshot = state.snapshot(101);
            bookHash = state.matchingFlow.matchingStateHashAsync().join();
        }

        // 完整解析原快照得到的公开配对清单。
        CoreSnapshotManifest manifest = TradingCoreRuntime.inspectSnapshot(productLine, snapshot);
        try (/* 从同产品线快照恢复并激活的运行时，退出时关闭。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
            assertThat(manifest.productLine()).isEqualTo(productLine);
            assertThat(manifest.topology().routeVersion()).isEqualTo(3);
            assertThat(restored.productLine()).isEqualTo(productLine);
            assertThat(restored.matchingFlow.matchingStateHashAsync().join()).isEqualTo(bookHash);
        }
    }
}
