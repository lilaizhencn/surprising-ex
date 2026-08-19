package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public final class RuntimeIdentityRegistry {

    private final Map<String, Integer> assetIds = new HashMap<>();
    private final Map<Integer, String> assets = new HashMap<>();
    private final Map<String, Integer> symbolIds = new HashMap<>();
    private final Map<Integer, String> symbols = new HashMap<>();
    private final Map<ClientIdentity, Long> clientKeys = new HashMap<>();
    private final Map<Long, ClientIdentity> clients = new HashMap<>();
    private final Map<PositionIdentity, Long> positionKeys = new HashMap<>();
    private final Map<Long, PositionIdentity> positions = new HashMap<>();
    private int nextAssetId;
    private int nextSymbolId;
    private long nextClientKey = 1;
    private long nextPositionKey = 1;

    public int assetId(String asset) {
        String normalized = AssetBalance.normalizeAsset(asset);
        Integer existing = assetIds.get(normalized);
        if (existing != null) return existing;
        int id = nextAssetId++;
        assetIds.put(normalized, id);
        assets.put(id, normalized);
        return id;
    }

    public String asset(int assetId) {
        String asset = assets.get(assetId);
        if (asset == null) throw new IllegalArgumentException("unknown runtime asset id: " + assetId);
        return asset;
    }

    public int symbolId(String symbol) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        Integer existing = symbolIds.get(normalized);
        if (existing != null) return existing;
        int id = nextSymbolId++;
        symbolIds.put(normalized, id);
        symbols.put(id, normalized);
        return id;
    }

    public String symbol(int symbolId) {
        String symbol = symbols.get(symbolId);
        if (symbol == null) throw new IllegalArgumentException("unknown runtime symbol id: " + symbolId);
        return symbol;
    }

    public long clientKey(long userId, String clientOrderId) {
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

    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        ClientIdentity identity = clients.get(clientKey);
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + clientKey);
        }
        return identity.clientOrderId();
    }

    public long positionKey(long userId, String positionKey) {
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

    public String positionKey(long userId, long positionKey) {
        PositionIdentity identity = positions.get(positionKey);
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    public Snapshot snapshot() {
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

    public record PositionIdentity(long userId, String positionKey) implements Comparable<PositionIdentity> {
        @Override
        public int compareTo(PositionIdentity other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : positionKey.compareTo(other.positionKey);
        }
    }
}
