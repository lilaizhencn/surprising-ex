package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeIdentityRegistryTest {

    @Test
    void lanesKeepReadingPreparedKeysWhileOwnerExpandsTheDictionary() throws Exception {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long[] keys = new long[256];
        for (int index = 0; index < keys.length; index++) {
            keys[index] = identities.positionKey(index + 1, "BTC-USDT:NET");
        }
        var started = new java.util.concurrent.CountDownLatch(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int lane = 0; lane < 4; lane++) {
                results.add(workers.submit(() -> {
                    started.countDown();
                    start.await();
                    for (int index = 0; index < 100_000; index++) {
                        int slot = index & 255;
                        long actual = identities.preparedPositionKey(slot + 1, "BTC-USDT:NET");
                        if (actual != keys[slot]) throw new AssertionError("prepared identity changed");
                    }
                    return null;
                }));
            }
            try {
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (int index = 257; index < 50_000; index++) {
                identities.positionKey(index, "BTC-USDT:NET");
            }
            for (var result : results) result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void dictionaryVersionAdvancesOnlyWhenStableIdentitiesAreAllocatedAndSurvivesRestore() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long initial = identities.dictionaryVersion();
        int assetId = identities.assetId("USDT");
        int symbolId = identities.symbolId("BTC-USDT");
        identities.assetId("USDT");
        identities.symbolId("BTC-USDT");

        assertThat(identities.dictionaryVersion()).isEqualTo(initial + 2);
        RuntimeIdentityRegistry restored = RuntimeIdentityRegistry.restore(identities.snapshot());
        assertThat(restored.asset(assetId)).isEqualTo("USDT");
        assertThat(restored.symbol(symbolId)).isEqualTo("BTC-USDT");
        assertThat(restored.dictionaryVersion()).isEqualTo(identities.dictionaryVersion());
    }

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

    @Test
    void clientIdentityReferenceProtectsAReusedKeyFromAnOlderTerminalRelease() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        var first = identities.prepareClientKey(1001, "client-1");
        var reused = identities.prepareClientKey(1001, "client-1");

        identities.releaseClientKey(1001, first.key());

        assertThat(identities.clientIdentityCount()).isOne();
        assertThat(identities.clientOrderId(1001, reused.key())).isEqualTo("client-1");

        identities.rollbackPreparedClientKey(1001, "client-1", reused);
        assertThat(identities.clientIdentityCount()).isZero();
    }
}
