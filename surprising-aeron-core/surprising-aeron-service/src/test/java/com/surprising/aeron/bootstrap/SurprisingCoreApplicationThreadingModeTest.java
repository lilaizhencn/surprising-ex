package com.surprising.aeron.bootstrap;

import io.aeron.cluster.client.ClusterEvent;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.ErrorHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 检查驱动线程模式、存活超时和启动失败退出，避免配置错误留下仍在运行的节点进程。 */
class SurprisingCoreApplicationThreadingModeTest {

    /** 被测试的 MediaDriver 线程模式属性；每个测试结束后清除，避免串扰。 */
    private static final String PROPERTY = "surprising.aeron.core.threading-mode";
    /** 当前测试独占的驱动目录和子进程日志目录，由 JUnit 在测试结束时清理。 */
    @TempDir
    Path tempDirectory;

    /** 清掉本类修改的线程模式配置，让后续测试重新读取自己的配置或默认值。 */
    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    /** 显式配置 DEDICATED 时应使用独立驱动线程模式。 */
    @Test
    void acceptsConfiguredThreadingMode() {
        System.setProperty(PROPERTY, "DEDICATED");

        assertThat(AeronCoreLifecycle.coreThreadingMode()).isEqualTo(ThreadingMode.DEDICATED);
    }

    /** 无法识别的驱动模式必须报告配置错误，不能偷偷换一个模式继续启动。 */
    @Test
    void rejectsUnknownThreadingMode() {
        System.setProperty(PROPERTY, "invalid");

        assertThatIllegalArgumentException().isThrownBy(AeronCoreLifecycle::coreThreadingMode)
                .withMessageContaining("valid Aeron ThreadingMode");
    }

    /** 检查客户端存活超时固定为 30 秒，防止诊断暂停被过短超时误判为掉线。 */
    @Test
    void defaultsClientLivenessAboveTheObservedDiagnosticFreeze() throws Exception {
        // 反射读取包内配置函数，验证实际返回值而不是在测试中重写一份计算。
        Method method = AeronCoreLifecycle.class.getDeclaredMethod("coreClientLivenessTimeoutNs");
        method.setAccessible(true);

        assertThat((long) method.invoke(null)).isEqualTo(TimeUnit.SECONDS.toNanos(30));
    }

    /** Publication 解阻等待必须长于客户端存活超时，避免过早解阻正在恢复的客户端。 */
    @Test
    void keepsPublicationUnblockingAboveClientLiveness() throws Exception {
        // 读取驱动实际使用的解阻配置，与存活配置直接比较。
        Method method = AeronCoreLifecycle.class.getDeclaredMethod("corePublicationUnblockTimeoutNs");
        method.setAccessible(true);

        assertThat((long) method.invoke(null))
                .isGreaterThan(AeronCoreLifecycle.coreClientLivenessTimeoutNs());
    }

    /** 共识模块发出普通心跳警告时，不应请求终止 JVM。 */
    @Test
    void doesNotTerminateOnAeronWarning() {
        // 保存终止请求；测试用这个值判断处理器是否错误地触发了退出。
        AtomicInteger exit = new AtomicInteger();
        // 使用真实错误分类逻辑，只替换最终的进程退出动作。
        ErrorHandler handler = AeronCoreLifecycle.errorHandler("consensus-module", exit::set);
        handler.onError(new ClusterEvent("leader heartbeat timeout"));
        assertThat(exit).hasValue(0);
    }

    /** 先占用驱动目录，再启动真实 Core，确认驱动无法启动时子进程会自行退出而不是挂住。 */
    @Test
    void exitsWhenTheMediaDriverCannotStart() throws Exception {
        // 子进程通过 aeron.dir 读取的基础目录名，拓扑还会追加产品线与节点编号。
        String aeronBaseDirectory = tempDirectory.resolve("aeron-driver").toString();
        // 先在这个最终节点目录启动驱动，制造可明确识别的目录占用冲突。
        String nodeAeronDirectory = aeronBaseDirectory + "-surprising-spot-2";
        // 测试结束时删除本测试创建的驱动目录，不接触用户已有节点的数据。
        MediaDriver.Context context = new MediaDriver.Context()
                .aeronDirectoryName(nodeAeronDirectory)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        // 留住子进程句柄，成功和异常路径都能在 finally 中确保它已经停止。
        Process process = null;
        try (/* 占住节点驱动目录的真实 MediaDriver，退出作用域时关闭。 */
                MediaDriver ignored = MediaDriver.launch(context)) {
            // 收集子进程启动信息，断言失败时可以直接看到它为什么没有按预期退出。
            Path childOutput = tempDirectory.resolve("core-startup.log");
            // 给子进程提供与当前测试相同的实际依赖，确保运行的是当前源码的启动入口。
            String testClasspath = System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-Daeron.dir=" + aeronBaseDirectory,
                    "-Dsurprising.aeron.product-line=SPOT",
                    "-Dsurprising.aeron.node-id=2",
                    "-Dsurprising.aeron.hostnames=127.0.0.1,127.0.0.1,127.0.0.1",
                    "-Dsurprising.aeron.data-dir=" + tempDirectory.resolve("cluster-data"),
                    "-cp", testClasspath,
                    SurprisingCoreApplication.class.getName())
                    .redirectErrorStream(true)
                    .redirectOutput(childOutput.toFile())
                    .start();

            // 给冷 JVM 和 Spring 装配留出时间，也兼顾同机有其他集成测试的情况。
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            // 一次读出启动输出，用于断言错误类型以及在失败信息中还原现场。
            String output = Files.readString(childOutput);
            assertThat(exited)
                    .as("failed Core startup must not leave threads running; child output:%n%s", output)
                    .isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(output).contains("ActiveDriverException");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
