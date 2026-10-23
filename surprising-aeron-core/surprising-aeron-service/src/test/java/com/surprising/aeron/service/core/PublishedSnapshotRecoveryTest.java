package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** 固定旧格式样本只用于验证版本边界拒绝；当前格式往返由现有 Core 快照用例覆盖。 */
class PublishedSnapshotRecoveryTest {
    /** 未上线旧数据不保留读取入口，固定 v27 样本在外层版本检查时拒绝。 */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectsRetiredV27SnapshotSchema(ProductLine product) throws Exception {
        byte[] snapshot = read("/recovery/v27-probe-removal-20261018/" + product.name() + ".snapshot");
        assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(product, snapshot))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported snapshot version: 27");
    }

    /**
     * 逐产品线读取固定 v26 旧样本，要求在外层格式检查时以不支持二十六版拒绝，不进入组件恢复。
     * @param product 本次参数化覆盖的产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectsRetiredProbeContainingSnapshotSchema(ProductLine product) throws Exception {
        // 需要在外层版本边界明确拒绝的旧样本字节。
        byte[] old = read("/recovery/v26-v36-274495ae/" + product.name() + ".snapshot");
        assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(product, old))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported snapshot version: 26");
    }

    /**
     * 对两个固定旧划转样本要求同样在 v26 外层边界拒绝；不是验证新外层格式内部旧划转负载的兼容转换。
     */
    @org.junit.jupiter.api.Test
    void rejectsOldTransferSnapshotsAtTheSchemaBoundary() throws Exception {
        for (String /* 当前待检查的固定样本文件名或完整路径。 */ name : new String[]{"published-v1-transfer", "published-v1-multi"}) {
            // 需要在外层版本边界明确拒绝的旧样本字节。
            byte[] old = read("/recovery/a8df45ae/" + name + ".snapshot");
            assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, old))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported snapshot version: 26");
        }
    }

    /**
     * 按完整 classpath 资源名读取全部字节，资源缺失明确抛 IOException，流由 try 自动关闭。
     * @param name 包含目录的完整 classpath 样本路径
     */
    private static byte[] read(String name) throws IOException {
        try (/* classpath 资源输入流，读取结束或异常时自动关闭。 */ var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }
}
