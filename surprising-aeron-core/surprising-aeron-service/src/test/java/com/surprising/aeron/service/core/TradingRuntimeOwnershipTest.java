package com.surprising.aeron.service.core;

import com.surprising.product.api.ProductLine;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖六产品线运行时的关闭所有权：外部线程不能关闭 Owner 的状态，拒绝错误调用后，原 Owner 仍能读取并正常关闭。
 */
class TradingRuntimeOwnershipTest {
    /**
     * 先激活运行时并保存业务哈希，再由外部线程尝试关闭；断言收到跨线程错误、状态未关闭且哈希未变，最后由正确 Owner 关闭成功。
     * @param product 当前参数化场景的产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedForeignCloseDoesNotPoisonTheOwnerLifecycle(ProductLine product) throws Exception {
        try (/* 当前测试自动关闭的真实运行时 */ var runtime = new TradingCoreRuntime(product)) {
            runtime.activate();
            // 错误线程调用前保存的业务状态哈希
            long initialHash = runtime.stateHash();
            // 外部线程把关闭异常交回测试的原子引用
            var rejected = new AtomicReference<Throwable>();
            // 故意跨越所有权调用 close 的外部线程
            Thread thread = Thread.ofPlatform().start(() -> {
                try { runtime.close(); }
                catch (/* 外部线程捕获并交回测试断言的关闭错误 */ Throwable failure) { rejected.set(failure); }
            });
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(rejected.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("another thread");
            assertThat(runtime.closed).as("a foreign caller must not claim shutdown").isFalse();
            assertThat(runtime.stateHash()).isEqualTo(initialHash);
            runtime.close();
            assertThat(runtime.closed).isTrue();
        }
    }
}
