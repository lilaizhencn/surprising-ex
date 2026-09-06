package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SurprisingAeronClientMediaDriverTest {

    @Test
    void standaloneClientsUseIndependentMediaDriverDirectories() {
        try (var first = SurprisingAeronClient.newMediaDriver();
             var second = SurprisingAeronClient.newMediaDriver()) {
            assertThat(first.aeronDirectoryName()).isNotEqualTo(second.aeronDirectoryName());
        }
    }

    @Test
    void repeatedConnectionTimeoutsCloseAeronBeforeTheOwnedDriver() throws Exception {
        String prefix = "surprising-aeron-client-" + ProcessHandle.current().pid() + "-";
        java.util.Set<String> before = driverDirectories(prefix);
        for (int attempt = 0; attempt < 3; attempt++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> SurprisingAeronClient.connect(
                    com.surprising.product.api.ProductLine.SPOT,
                    java.util.List.of("127.0.0.1", "127.0.0.1", "127.0.0.1"),
                    "127.0.0.1", java.time.Duration.ofMillis(200)))
                    .isInstanceOf(io.aeron.exceptions.TimeoutException.class);
            assertThat(driverDirectories(prefix)).isEqualTo(before);
        }
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("surprising-aeron-connect-"));
    }

    private static java.util.Set<String> driverDirectories(String prefix) throws java.io.IOException {
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of("."))) {
            return paths.map(path -> path.getFileName().toString()).filter(name -> name.startsWith(prefix))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void serializesEveryAeronClusterOperationToPreventTruncatedIngressFrames() throws Exception {
        assertThat(Modifier.isSynchronized(SurprisingAeronClient.class
                .getDeclaredMethod("offer", com.surprising.aeron.protocol.CoreMessage.class).getModifiers())).isTrue();
        assertThat(Modifier.isSynchronized(SurprisingAeronClient.class
                .getDeclaredMethod("pollEgress", int.class).getModifiers())).isTrue();
        assertThat(Modifier.isSynchronized(SurprisingAeronClient.class
                .getDeclaredMethod("keepAlive").getModifiers())).isTrue();
        assertThat(Modifier.isSynchronized(SurprisingAeronClient.class
                .getDeclaredMethod("close").getModifiers())).isTrue();
        assertThat(Modifier.isSynchronized(SurprisingAeronClient.class
                .getDeclaredMethod("pollAndCheckSession").getModifiers())).isTrue();
    }
}
