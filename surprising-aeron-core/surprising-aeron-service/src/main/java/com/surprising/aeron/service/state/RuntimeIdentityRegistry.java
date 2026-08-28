package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeIdentityRegistry {

    private final Map<String, Integer> assetIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> assets = new ConcurrentHashMap<>();
    private final Map<String, Integer> symbolIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> symbols = new ConcurrentHashMap<>();
    private final Map<ClientIdentity, Long> clientKeys = new ConcurrentHashMap<>();
    private final Map<Long, ClientIdentity> clients = new ConcurrentHashMap<>();
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    private final Map<Long, PositionIdentity> positions = new ConcurrentHashMap<>();
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

    void releaseOwnerForHandoff() {
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
        long key = nextClientKey++;
        clientKeys.put(identity, key);
        clients.put(key, identity);
        return key;
    }

    public PreparedClientKey prepareClientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return new PreparedClientKey(0, false);
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        Long existing = clientKeys.get(identity);
        if (existing != null) return new PreparedClientKey(existing, false);
        long key = nextClientKey++;
        clientKeys.put(identity, key);
        clients.put(key, identity);
        return new PreparedClientKey(key, true);
    }

    public void rollbackPreparedClientKey(
            long userId, String clientOrderId, PreparedClientKey prepared) {
        assertOwner();
        if (userId <= 0 || clientOrderId == null || prepared == null || !prepared.allocated()) {
            throw new IllegalArgumentException("invalid prepared client key rollback");
        }
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        if (prepared.key() == 0 || prepared.key() != nextClientKey - 1
                || !Long.valueOf(prepared.key()).equals(clientKeys.get(identity))
                || !identity.equals(clients.get(prepared.key()))) {
            throw new IllegalStateException("prepared client key is no longer rollback-safe");
        }
        clientKeys.remove(identity);
        clients.remove(prepared.key());
        nextClientKey = prepared.key();
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

    public long positionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) {
            throw new IllegalArgumentException("invalid position identity");
        }
        PositionIdentity identity = new PositionIdentity(userId, positionKey);
        Long existing = positionKeys.get(identity);
        if (existing != null) return existing;
        long key = nextPositionKey++;
        positionKeys.put(identity, key);
        positions.put(key, identity);
        return key;
    }

    public Long findPositionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) return null;
        return positionKeys.get(new PositionIdentity(userId, positionKey));
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

    public Snapshot snapshot() {
        assertOwner();
        return new Snapshot(assetIds, symbolIds, clientKeys, positionKeys,
                nextAssetId, nextSymbolId, nextClientKey, nextPositionKey);
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
