package com.surprising.aeron.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.aeron.cluster.client.ClusterEvent;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import com.surprising.aeron.service.SurprisingCoreApplication;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.ErrorHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurprisingCoreApplicationThreadingModeTest {

    private static final String PROPERTY = "surprising.aeron.core.threading-mode";
    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void acceptsConfiguredThreadingMode() {
        System.setProperty(PROPERTY, "DEDICATED");

        assertThat(AeronCoreLifecycle.coreThreadingMode()).isEqualTo(ThreadingMode.DEDICATED);
    }

    @Test
    void rejectsUnknownThreadingMode() {
        System.setProperty(PROPERTY, "invalid");

        assertThatIllegalArgumentException().isThrownBy(AeronCoreLifecycle::coreThreadingMode)
                .withMessageContaining("valid Aeron ThreadingMode");
    }

    @Test
    void defaultsClientLivenessAboveTheObservedDiagnosticFreeze() throws Exception {
        Method method = AeronCoreLifecycle.class.getDeclaredMethod("coreClientLivenessTimeoutNs");
        method.setAccessible(true);

        assertThat((long) method.invoke(null)).isEqualTo(TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    void keepsPublicationUnblockingAboveClientLiveness() throws Exception {
        Method method = AeronCoreLifecycle.class.getDeclaredMethod("corePublicationUnblockTimeoutNs");
        method.setAccessible(true);

        assertThat((long) method.invoke(null))
                .isGreaterThan(AeronCoreLifecycle.coreClientLivenessTimeoutNs());
    }

    @Test
    void doesNotTerminateOnAeronWarning() {
        AtomicInteger exit = new AtomicInteger();
        ErrorHandler handler = AeronCoreLifecycle.errorHandler("consensus-module", exit::set);
        handler.onError(new ClusterEvent("leader heartbeat timeout"));
        assertThat(exit).hasValue(0);
    }

    @Test
    void exitsWhenTheMediaDriverCannotStart() throws Exception {
        String aeronBaseDirectory = tempDirectory.resolve("aeron-driver").toString();
        String nodeAeronDirectory = aeronBaseDirectory + "-surprising-spot-2";
        MediaDriver.Context context = new MediaDriver.Context()
                .aeronDirectoryName(nodeAeronDirectory)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        Process process = null;
        try (MediaDriver ignored = MediaDriver.launch(context)) {
            Path childOutput = tempDirectory.resolve("core-startup.log");
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

            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
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
