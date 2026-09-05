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
