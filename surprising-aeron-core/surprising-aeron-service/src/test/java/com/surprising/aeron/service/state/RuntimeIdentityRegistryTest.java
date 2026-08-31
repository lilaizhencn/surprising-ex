package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeIdentityRegistryTest {

    @Test
    void rollbackPositionKeysSkipsAllocationsAlreadyReleasedAfterCheckpoint() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.positionKey(1001, "BTC-USDT:NET");
        long checkpoint = identities.positionCheckpoint();
        long released = identities.positionKey(1002, "BTC-USDT:NET");
        identities.positionKey(1003, "ETH-USDT:NET");

        identities.releasePositionKey(released);
        identities.rollbackPositionKeys(checkpoint);

        assertThat(identities.findPositionKey(1001, "BTC-USDT:NET")).isNotNull();
        assertThat(identities.findPositionKey(1002, "BTC-USDT:NET")).isNull();
        assertThat(identities.findPositionKey(1003, "ETH-USDT:NET")).isNull();
        assertThat(identities.positionCheckpoint()).isEqualTo(checkpoint);
    }
}
