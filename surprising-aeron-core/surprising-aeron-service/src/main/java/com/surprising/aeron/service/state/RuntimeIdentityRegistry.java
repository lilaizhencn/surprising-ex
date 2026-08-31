package com.surprising.aeron.service.state;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeIdentityRegistry implements RuntimeCommitPatch.IdentityView {

    private final Map<String, Integer> assetIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> assets = new ConcurrentHashMap<>();
    private final Map<String, Integer> symbolIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> symbols = new ConcurrentHashMap<>();
    private final Map<ClientIdentity, Long> clientKeys = new ConcurrentHashMap<>();
    private final Map<Long, ClientIdentity> clients = new ConcurrentHashMap<>();
    private final Map<Long, Long> clientAllocationKeys = new ConcurrentHashMap<>();
    private final Map<Long, Long> clientKeyAllocations = new ConcurrentHashMap<>();
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    private final Map<Long, PositionIdentity> positions = new ConcurrentHashMap<>();
    private final Map<Long, Long> positionAllocationKeys = new ConcurrentHashMap<>();
    private final Map<Long, Long> positionKeyAllocations = new ConcurrentHashMap<>();
    private int nextAssetId;
    private int nextSymbolId;
    private long nextClientKey = 1;
    private long nextPositionKey = 1;
    private Thread owner;

    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("runtime identities are bound to another thread");
    }

    public void releaseOwnerForHandoff() {
        owner = null;
    }

    public int assetId(String asset) {
        assertOwner();
        String normalized = AssetBalance.normalizeAsset(asset);
        Integer existing = assetIds.get(normalized);
        if (existing != null) return existing;
        int id = nextAssetId++;
        assetIds.put(normalized, id);
        assets.put(id, normalized);
        return id;
    }

    public Integer findAssetId(String asset) {
        assertOwner();
        return assetIds.get(AssetBalance.normalizeAsset(asset));
    }

    public String asset(int assetId) {
        String asset = assets.get(assetId);
        if (asset == null) throw new IllegalArgumentException("unknown runtime asset id: " + assetId);
        return asset;
    }

    public int symbolId(String symbol) {
        assertOwner();
        String normalized = OrderReservation.normalizeSymbol(symbol);
        Integer existing = symbolIds.get(normalized);
        if (existing != null) return existing;
        int id = nextSymbolId++;
        symbolIds.put(normalized, id);
        symbols.put(id, normalized);
        return id;
    }

    public Integer findSymbolId(String symbol) {
        assertOwner();
        return symbolIds.get(OrderReservation.normalizeSymbol(symbol));
    }

    public String symbol(int symbolId) {
        return preparedSymbol(symbolId);
    }

    String preparedSymbol(int symbolId) {
        String symbol = symbols.get(symbolId);
        if (symbol == null) throw new IllegalArgumentException("unknown runtime symbol id: " + symbolId);
        return symbol;
    }

    public long clientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return 0;
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        Long existing = clientKeys.get(identity);
        if (existing != null) return existing;
        long key = deterministicKey(userId, clientOrderId);
        ClientIdentity collision = clients.putIfAbsent(key, identity);
        if (collision != null && !collision.equals(identity)) {
            throw new IllegalStateException("deterministic client identity collision");
        }
        clientKeys.put(identity, key);
        trackAllocation(clientAllocationKeys, clientKeyAllocations, nextClientKey++, key);
        return key;
    }

    public PreparedClientKey prepareClientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return new PreparedClientKey(0, false);
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        Long existing = clientKeys.get(identity);
        if (existing != null) return new PreparedClientKey(existing, false);
        long key = deterministicKey(userId, clientOrderId);
        ClientIdentity collision = clients.putIfAbsent(key, identity);
        if (collision != null && !collision.equals(identity)) {
            throw new IllegalStateException("deterministic client identity collision");
        }
        clientKeys.put(identity, key);
        trackAllocation(clientAllocationKeys, clientKeyAllocations, nextClientKey++, key);
        return new PreparedClientKey(key, true);
    }

    public void rollbackPreparedClientKey(
            long userId, String clientOrderId, PreparedClientKey prepared) {
        assertOwner();
        if (userId <= 0 || clientOrderId == null || prepared == null || !prepared.allocated()) {
            throw new IllegalArgumentException("invalid prepared client key rollback");
        }
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        long allocation = nextClientKey - 1;
        if (prepared.key() == 0 || !Long.valueOf(prepared.key()).equals(clientAllocationKeys.get(allocation))
                || !Long.valueOf(prepared.key()).equals(clientKeys.get(identity))
                || !identity.equals(clients.get(prepared.key()))) {
            throw new IllegalStateException("prepared client key is no longer rollback-safe");
        }
        clientKeys.remove(identity);
        clients.remove(prepared.key());
        clientAllocationKeys.remove(allocation);
        clientKeyAllocations.remove(prepared.key(), allocation);
        nextClientKey = allocation;
    }

    public Long findClientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return null;
        return clientKeys.get(new ClientIdentity(userId, clientOrderId));
    }

    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        ClientIdentity identity = clients.get(clientKey);
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + clientKey);
        }
        return identity.clientOrderId();
    }

    public void releaseClientKey(long userId, long clientKey) {
        assertOwner();
        ClientIdentity identity = clients.get(clientKey);
        if (identity == null || identity.userId() != userId) return;
        clients.remove(clientKey, identity);
        clientKeys.remove(identity, clientKey);
        removeAllocation(clientAllocationKeys, clientKeyAllocations, clientKey);
    }

    public long positionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) {
            throw new IllegalArgumentException("invalid position identity");
        }
        PositionIdentity identity = new PositionIdentity(userId, positionKey);
        Long existing = positionKeys.get(identity);
        if (existing != null) return existing;
        long key = deterministicPositionKey(identity);
        PositionIdentity collision = positions.putIfAbsent(key, identity);
        if (collision != null && !collision.equals(identity)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        positionKeys.put(identity, key);
        trackAllocation(positionAllocationKeys, positionKeyAllocations, nextPositionKey++, key);
        return key;
    }

    public Long findPositionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) return null;
        return positionKeys.get(new PositionIdentity(userId, positionKey));
    }

    public long positionCheckpoint() {
        assertOwner();
        return nextPositionKey;
    }

    public void rollbackPositionKeys(long checkpoint) {
        assertOwner();
        if (checkpoint <= 0 || checkpoint > nextPositionKey) {
            throw new IllegalArgumentException("invalid position identity checkpoint");
        }
        while (nextPositionKey > checkpoint) {
            long allocation = --nextPositionKey;
            Long key = positionAllocationKeys.remove(allocation);
            if (key == null) continue;
            positionKeyAllocations.remove(key, allocation);
            PositionIdentity identity = positions.remove(key);
            if (identity == null || !Long.valueOf(key).equals(positionKeys.remove(identity))) {
                throw new IllegalStateException("position identity checkpoint is inconsistent");
            }
        }
    }

    private static long deterministicPositionKey(PositionIdentity identity) {
        long hash = 0xcbf29ce484222325L;
        long userId = identity.userId();
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ (userId >>> shift & 0xffL)) * 0x100000001b3L;
        }
        for (byte value : identity.positionKey().getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (value & 0xffL)) * 0x100000001b3L;
        }
        long key = hash & Long.MAX_VALUE;
        return key == 0 ? 1 : key;
    }

    long preparedPositionKey(long userId, String positionKey) {
        Long key = positionKeys.get(new PositionIdentity(userId, positionKey));
        if (key == null) throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    public String positionKey(long userId, long positionKey) {
        PositionIdentity identity = positions.get(positionKey);
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    public PositionIdentity positionIdentity(long positionKey) {
        PositionIdentity identity = positions.get(positionKey);
        if (identity == null) {
            throw new IllegalArgumentException("unknown runtime position key: " + positionKey);
        }
        return identity;
    }

    public void releasePositionKey(long positionKey) {
        assertOwner();
        PositionIdentity identity = positions.remove(positionKey);
        if (identity == null) return;
        positionKeys.remove(identity, positionKey);
        removeAllocation(positionAllocationKeys, positionKeyAllocations, positionKey);
    }

    public Snapshot snapshot() {
        assertOwner();
        return new Snapshot(assetIds, symbolIds, clientKeys, positionKeys,
                nextAssetId, nextSymbolId, clientKeys.size() + 1L, positionKeys.size() + 1L);
    }

    private static long deterministicKey(long userId, String value) {
        long hash = 0xcbf29ce484222325L;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ (userId >>> shift & 0xffL)) * 0x100000001b3L;
        }
        for (byte character : value.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (character & 0xffL)) * 0x100000001b3L;
        }
        long key = hash & Long.MAX_VALUE;
        return key == 0 ? 1 : key;
    }

    private static void trackAllocation(Map<Long, Long> allocations, Map<Long, Long> allocationsByKey,
                                        long allocation, long key) {
        Long previousKey = allocations.putIfAbsent(allocation, key);
        Long previousAllocation = allocationsByKey.putIfAbsent(key, allocation);
        if (previousKey != null || previousAllocation != null) {
            if (previousKey == null) allocations.remove(allocation, key);
            if (previousAllocation == null) allocationsByKey.remove(key, allocation);
            throw new IllegalStateException("runtime identity allocation collision");
        }
    }

    private static void removeAllocation(Map<Long, Long> allocations, Map<Long, Long> allocationsByKey,
                                         long key) {
        Long allocation = allocationsByKey.remove(key);
        if (allocation == null) return;
        if (!allocations.remove(allocation, key)) {
            throw new IllegalStateException("runtime identity allocation index is inconsistent");
        }
    }

    public static RuntimeIdentityRegistry restore(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("identity snapshot is required");
        RuntimeIdentityRegistry registry = new RuntimeIdentityRegistry();
        snapshot.assetIds().forEach((name, id) -> {
            registry.assetIds.put(name, id);
            registry.assets.put(id, name);
        });
        snapshot.symbolIds().forEach((name, id) -> {
            registry.symbolIds.put(name, id);
            registry.symbols.put(id, name);
        });
        snapshot.clientKeys().forEach((identity, key) -> {
            registry.clientKeys.put(identity, key);
            registry.clients.put(key, identity);
        });
        snapshot.positionKeys().forEach((identity, key) -> {
            registry.positionKeys.put(identity, key);
            registry.positions.put(key, identity);
        });
        registry.nextAssetId = snapshot.nextAssetId();
        registry.nextSymbolId = snapshot.nextSymbolId();
        registry.nextClientKey = snapshot.nextClientKey();
        registry.nextPositionKey = snapshot.nextPositionKey();
        return registry;
    }

    public record Snapshot(Map<String, Integer> assetIds, Map<String, Integer> symbolIds,
                           Map<ClientIdentity, Long> clientKeys, Map<PositionIdentity, Long> positionKeys,
                           int nextAssetId, int nextSymbolId, long nextClientKey, long nextPositionKey) {
        public Snapshot {
            assetIds = Collections.unmodifiableMap(new TreeMap<>(assetIds));
            symbolIds = Collections.unmodifiableMap(new TreeMap<>(symbolIds));
            clientKeys = Collections.unmodifiableMap(new TreeMap<>(clientKeys));
            positionKeys = Collections.unmodifiableMap(new TreeMap<>(positionKeys));
            if (nextAssetId < assetIds.size() || nextSymbolId < symbolIds.size()
                    || nextClientKey <= clientKeys.size() || nextPositionKey <= positionKeys.size()) {
                throw new IllegalArgumentException("invalid identity snapshot cursors");
            }
        }
    }

    public record ClientIdentity(long userId, String clientOrderId) implements Comparable<ClientIdentity> {
        @Override
        public int compareTo(ClientIdentity other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : clientOrderId.compareTo(other.clientOrderId);
        }
    }

    public record PreparedClientKey(long key, boolean allocated) {
        public PreparedClientKey {
            if (key < 0 || (key == 0 && allocated)) {
                throw new IllegalArgumentException("invalid prepared client key");
            }
        }
    }

    public record PositionIdentity(long userId, String positionKey) implements Comparable<PositionIdentity> {
        @Override
        public int compareTo(PositionIdentity other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : positionKey.compareTo(other.positionKey);
        }
    }
}
