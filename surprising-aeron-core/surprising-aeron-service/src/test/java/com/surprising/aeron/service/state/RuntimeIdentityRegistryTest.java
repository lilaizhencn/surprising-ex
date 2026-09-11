package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeIdentityRegistryTest {
    @Test
    void preparedReleasePreservesReferencesAndCannotRemoveRecreatedIdentity() throws Exception {
        var registry = new RuntimeIdentityRegistry();
        long user = 17;
        var lane = new AccountLaneState(LaneTopology.configured(false).accountLaneId(user), 16);
        var buffer = new TradingRuntimeState.PublishedLaneChanges.ClientIdentityReleaseBuffer();
        var first = registry.prepareClientKeyInLane(lane, user, "客户-retired");
        registry.prepareClientKeyInLane(lane, user, "客户-retired");
        var entry = registry.prepareClientRelease(lane, user, first.key());
        buffer.add(entry);
        assertThat(registry.clientIdentityCount()).isEqualTo(1);
        buffer.release(registry);
        assertThat(registry.findClientKey(user, "客户-retired")).isEqualTo(first.key());
        assertThat(buffer.entries).containsOnlyNulls();
        registry.releasePreparedClientKey(entry);
        assertThat(registry.clientIdentityCount()).isZero();
        var recreated = registry.prepareClientKeyInLane(lane, user, "客户-retired");
        registry.releasePreparedClientKey(entry);
        assertThat(registry.findClientKey(user, "客户-retired")).isEqualTo(recreated.key());
        var restored = RuntimeIdentityRegistry.restore(registry.snapshot());
        var restoredEntry = restored.prepareClientRelease(lane, user, recreated.key());
        restored.releasePreparedClientKey(restoredEntry);
        assertThat(restored.clientIdentityCount()).isZero();
        assertThat(registry.clientIdentityCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.prepareClientRelease(
                new AccountLaneState(lane.laneId() + 1, 16), user, recreated.key()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clientLookupIsThreadLocalAndReleasePreservesOtherKeysAfterRestore() throws Exception {
        var original = new RuntimeIdentityRegistry();
        long first = original.clientKey(7, "客户-A");
        long second = original.clientKey(8, "客户-B");
        for (var registry : new RuntimeIdentityRegistry[]{original, RuntimeIdentityRegistry.restore(original.snapshot())}) {
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var a = executor.submit(() -> { for (int i = 0; i < 10000; i++) assertThat(registry.clientOrderId(7, first)).isEqualTo("客户-A"); });
                var b = executor.submit(() -> { for (int i = 0; i < 10000; i++) assertThat(registry.clientOrderId(8, second)).isEqualTo("客户-B"); });
                a.get(5, java.util.concurrent.TimeUnit.SECONDS);
                b.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            registry.releaseClientKey(8, first);
            assertThat(registry.findClientKey(7, "客户-A")).isEqualTo(first);
            registry.releaseClientKey(7, first);
            assertThat(registry.findClientKey(7, "客户-A")).isNull();
            assertThat(registry.clientOrderId(8, second)).isEqualTo("客户-B");
            registry.releaseClientKey(8, second);
            assertThat(registry.clientIdentityCount()).isZero();
        }
    }

    @Test
    void laneOwnsPreparationAndDuplicateRollbackPreservesLiveIdentity() {
        var identities = new RuntimeIdentityRegistry();
        long userId = 17;
        var topology = LaneTopology.configured(false);
        var lane = new AccountLaneState(topology.accountLaneId(userId), 16);
        var first = identities.prepareClientKeyInLane(lane, userId, "客户😀");
        var duplicate = identities.prepareClientKeyInLane(lane, userId, "客户😀");
        assertThat(duplicate.key()).isEqualTo(first.key());
        assertThat(lane.clientIdentityAllocations).isEqualTo(1);
        identities.rollbackClientKeyInLane(lane, userId, "客户😀", duplicate);
        assertThat(identities.clientIdentityCount()).isEqualTo(1);
        identities.rollbackClientKeyInLane(lane, userId, "客户😀", first);
        assertThat(identities.clientIdentityCount()).isZero();
        var wrongLane = new AccountLaneState(lane.laneId() + 1, 16);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                identities.prepareClientKeyInLane(wrongLane, userId, "wrong"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("crossed account Lane");
        assertThat(identities.clientIdentityCount()).isZero();
    }

    @Test
    void streamedIdentityHashExactlyMatchesJavaUtf8IncludingMalformedSurrogates() throws Exception {
        var method = RuntimeIdentityRegistry.class.getDeclaredMethod("deterministicKey", long.class, String.class);
        method.setAccessible(true);
        var values = new java.util.ArrayList<String>();
        values.addAll(java.util.List.of("", "client-123", "客户😀", "\ud800", "\udc00", "\ud800x\udc00", "\ud800\ud800\udc00"));
        for (int c = 0; c <= Character.MAX_VALUE; c++) values.add(String.valueOf((char)c));
        var random = new java.util.Random(91);
        for (int i = 0; i < 1_000; i++) {
            char[] chars = new char[16];
            for (int j = 0; j < chars.length; j++) chars[j] = (char)random.nextInt(65536);
            values.add(new String(chars));
        }
        for (String value : values) {
            long userId = random.nextLong() & Long.MAX_VALUE;
            long expected = 0xcbf29ce484222325L;
            for (int shift = 0; shift < 64; shift += 8)
                expected = (expected ^ (userId >>> shift & 255)) * 0x100000001b3L;
            for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                expected = (expected ^ (b & 255)) * 0x100000001b3L;
            expected &= Long.MAX_VALUE;
            assertThat((long)method.invoke(null, userId, value)).isEqualTo(expected == 0 ? 1 : expected);
        }
    }

    @Test
    void positionLookupSeparatesEqualHashesAndSurvivesRestoreAndRelease() {
        var identities = new RuntimeIdentityRegistry();
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        long first = identities.positionKey(7, "Aa");
        long second = identities.positionKey(7, "BB");
        long third = identities.positionKey(8, "Aa");
        long unicode = identities.positionKey(7, "客户😀:NET");
        for (var registry : new RuntimeIdentityRegistry[]{identities, RuntimeIdentityRegistry.restore(identities.snapshot())}) {
            assertThat(registry.positionKey(7, new String("Aa"))).isEqualTo(first);
            assertThat(registry.preparedPositionKey(7, "BB")).isEqualTo(second).isNotEqualTo(first);
            assertThat(registry.preparedPositionKey(8, "Aa")).isEqualTo(third).isNotEqualTo(first);
            assertThat(registry.findPositionKey(7, "客户😀:NET")).isEqualTo(unicode);
            registry.releasePositionKey(first);
            assertThat(registry.findPositionKey(7, "Aa")).isNull();
            assertThat(registry.findPositionKey(7, "BB")).isEqualTo(second);
        }
    }

    @Test
    void exactIdentityHitsPreserveNormalizationAndRejectInvalidMisses() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int symbol = identities.symbolId(" btc-usdt ");
        int asset = identities.assetId(" usdt ");
        long version = identities.dictionaryVersion();
        for (String value : new String[] {"BTC-USDT", new String("BTC-USDT"), "btc-usdt", " BTC-USDT "}) {
            assertThat(identities.symbolId(value)).isEqualTo(symbol);
            assertThat(identities.findSymbolId(value)).isEqualTo(symbol);
        }
        for (String value : new String[] {"USDT", new String("USDT"), "usdt", " USDT "}) {
            assertThat(identities.assetId(value)).isEqualTo(asset);
            assertThat(identities.findAssetId(value)).isEqualTo(asset);
        }
        assertThat(identities.dictionaryVersion()).isEqualTo(version);
        for (String value : new String[] {null, "", "BTC/USDT"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> identities.symbolId(value))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> identities.findSymbolId(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(identities.findSymbolId("ETH-USDT")).isNull();
    }

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
