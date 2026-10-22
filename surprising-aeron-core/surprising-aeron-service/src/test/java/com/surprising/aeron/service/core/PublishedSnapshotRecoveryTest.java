package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.snapshot.state.FundsStateHash;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用仓库中固定的已发布快照、原命令和预期哈希样本验证恢复。测试输入不由当前写入器现场生成；核对状态/资金哈希与资金命令幂等，再验证二次快照后相同契约，以及旧外层版本在格式入口被拒绝。
 */
class PublishedSnapshotRecoveryTest {
    /**
     * 逐产品线读取固定 v27 样本与预期哈希，恢复后重放原命令要求去重，篡改同 UUID 负载要求幂等冲突且状态/资金哈希不变；再保存恢复后二次快照，重复检查去重及冲突。
     * @param product 本次参数化覆盖的产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restoresCurrentFixedSnapshotAndRetainsFundsCommandIdempotency(ProductLine product) throws Exception {
        // 仓库保存的固定 v27 快照输入。
        byte[] snapshot = resource(product, ".snapshot");
        // 固定预期文件按换行分割的哈希文本。
        String[] expected = new String(resource(product, ".expected"), StandardCharsets.UTF_8).split("\\n");
        // 预期运行时状态哈希，按无符号十六进制读取到 long 位模式。
        long hash = Long.parseUnsignedLong(expected[0], 16);
        // 预期资金哈希，按无符号十六进制读取到 long 位模式。
        long fundsHash = Long.parseUnsignedLong(expected[1], 16);
        // 从固定命令文件解码的原消息，恢复后再次提交必须去重。
        var duplicate = CoreMessageCodec.decode(resource(product, ".command"));
        try (/* 固定样本恢复出的真实运行时，退出时关闭。 */ var restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
            assertThat(restored.stateHash()).isEqualTo(hash);
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            assertThat(restored.apply(duplicate).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(restored.stateHash()).isEqualTo(hash);
            // 用于构造冲突命令的负载字节，最后一字节翻转一位。
            byte[] changed = duplicate.payload();
            changed[changed.length - 1] ^= 1;
            // 沿用原 UUID 头但带不同负载的消息，要求返回幂等冲突。
            var conflicting = new CoreMessage(duplicate.header(), changed);
            assertThat(restored.apply(conflicting).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(restored.stateHash()).isEqualTo(hash);
            assertThat(FundsStateHash.compute(restored.snapshotTradingState())).isEqualTo(fundsHash);
            // 再保存并恢复一次当前快照，继续检查相同资金哈希、去重及冲突行为。
            try (/* 从恢复运行时再次生成的当前格式快照恢复的第二个运行时。 */ var upgraded = TradingCoreRuntime.fromSnapshot(product, restored.snapshot())) {
                assertThat(upgraded.stateHash()).isEqualTo(hash);
                assertThat(FundsStateHash.compute(upgraded.snapshotTradingState())).isEqualTo(fundsHash);
                assertThat(upgraded.apply(duplicate).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(upgraded.stateHash()).isEqualTo(hash);
                assertThat(upgraded.apply(conflicting).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(upgraded.stateHash()).isEqualTo(hash);
            }
        }
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

    /**
     * 从固定 v27 样本目录按产品线名和后缀读取资源，缺失时明确失败并自动关闭流。
     * @param product 本次参数化覆盖的产品线
     * @param suffix 需要读取的固定样本后缀，如快照、预期值或原命令
     */
    private static byte[] resource(ProductLine product, String suffix) throws IOException {
        // 当前待检查的固定样本文件名或完整路径。
        String name = "/recovery/v27-probe-removal-20261018/" + product.name() + suffix;
        try (/* classpath 资源输入流，读取结束或异常时自动关闭。 */ var input = PublishedSnapshotRecoveryTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixed recovery fixture: " + name);
            return input.readAllBytes();
        }
    }

}
