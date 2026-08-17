package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SurprisingAeronClientMediaDriverTest {

    @Test
    void standaloneClientsUseIndependentMediaDriverDirectories() {
        try (var first = SurprisingAeronClient.newMediaDriver();
             var second = SurprisingAeronClient.newMediaDriver()) {
            assertThat(first.aeronDirectoryName()).isNotEqualTo(second.aeronDirectoryName());
        }
    }
}
