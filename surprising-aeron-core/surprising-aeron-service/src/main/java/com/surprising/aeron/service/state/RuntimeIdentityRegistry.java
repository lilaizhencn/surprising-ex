package com.surprising.aeron.service.state;

import java.util.HashMap;
import java.util.Map;

public final class RuntimeIdentityRegistry {

    private final Map<String, Integer> assetIds = new HashMap<>();
    private final Map<String, Integer> symbolIds = new HashMap<>();
    private final Map<ClientIdentity, Long> clientKeys = new HashMap<>();
    private final Map<PositionIdentity, Long> positionKeys = new HashMap<>();
    private int nextAssetId;
    private int nextSymbolId;
    private long nextClientKey = 1;
    private long nextPositionKey = 1;

    public int assetId(String asset) {
        return assetIds.computeIfAbsent(AssetBalance.normalizeAsset(asset), ignored -> nextAssetId++);
    }

    public int symbolId(String symbol) {
        return symbolIds.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> nextSymbolId++);
    }

    public long clientKey(long userId, String clientOrderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return 0;
        ClientIdentity identity = new ClientIdentity(userId, clientOrderId);
        return clientKeys.computeIfAbsent(identity, ignored -> nextClientKey++);
    }

    public long positionKey(long userId, String positionKey) {
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) {
            throw new IllegalArgumentException("invalid position identity");
        }
        return positionKeys.computeIfAbsent(new PositionIdentity(userId, positionKey), ignored -> nextPositionKey++);
    }

    private record ClientIdentity(long userId, String clientOrderId) {
    }

    private record PositionIdentity(long userId, String positionKey) {
    }
}
