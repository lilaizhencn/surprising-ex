package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.protocol.CorePositionSide;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

public final class RuntimeIdentityRegistry implements RuntimeFactFrame.IdentityView {
    // Lookup-only keys never enter a map. Lanes and owner each borrow their own probe.
    private static final ThreadLocal<PositionLookup> POSITION_LOOKUP = ThreadLocal.withInitial(PositionLookup::new);

    private Long findPositionIdentity(long userId, String key) {
        PositionLookup lookup = POSITION_LOOKUP.get();
        lookup.userId = userId;
        lookup.key = key;
        try { return positionKeys.get(lookup); }
        finally { lookup.key = null; }
    }

    private static final class PositionLookup {
        long userId;
        String key;
        @Override public int hashCode() { return 31 * Long.hashCode(userId) + key.hashCode(); }
        @Override public boolean equals(Object other) {
            return other instanceof PositionIdentity identity
                    && userId == identity.userId() && key.equals(identity.positionKey());
        }
    }

    // Asset/symbol forward and allocation indexes are owner-only. Monotonic asset/symbol
    // dictionaries use volatile array publication; releasable client/position
    // identities remain concurrent for asynchronous Core Fact materializers.
    private final Map<String, Integer> assetIds = new HashMap<>();
    private volatile String[] assets = new String[16];
    private final Map<String, Integer> symbolIds = new HashMap<>();
    private volatile String[] symbols = new String[16];
    /** 校验异步身份准备确实在所属账户 Lane 执行。 */
    private LaneTopology clientTopology = LaneTopology.configured(false);
    private TradingRuntimeState clientRuntime;

    void bindClientLanes(TradingRuntimeState runtime) {
        assertOwner();
        if (clientRuntime != null && clientRuntime != runtime)
            throw new IllegalStateException("client identities already belong to a runtime");
        clientTopology = runtime.topology();
        clientRuntime = runtime;
    }

    private <T> T mutateClient(long userId, java.util.function.Supplier<T> mutation) {
        if (clientRuntime == null) { assertOwner(); return mutation.get(); }
        return clientRuntime.onLane(userId, lane -> mutation.get());
    }

    /** Reuse the current control task; never dispatch or wait for another Lane task. */
    public PreparedClientKey prepareClientKeyInCurrentLane(long userId, String name) {
        if (clientRuntime == null || clientRuntime.laneCommandScope.get() == null)
            throw new IllegalStateException("client identity requires the current Account Lane");
        return prepareClientKeyInLane(clientRuntime.laneCommandScope.get(), userId, name);
    }

    public void rollbackClientKeyInCurrentLane(long userId, String name, PreparedClientKey key) {
        if (clientRuntime == null || clientRuntime.laneCommandScope.get() == null)
            throw new IllegalStateException("client identity requires the current Account Lane");
        rollbackClientKeyInLane(clientRuntime.laneCommandScope.get(), userId, name, key);
    }
    /** Lane 创建、回滚和回收；Owner 仅在撮合前校验/查询边界读取不可变 identity。
     * 并发容器用于这些必要读者；引用计数仅由账户 Lane 修改。Fact 不再反查此表。 */
    private final Map<Long, ClientIdentityEntry> clients = new ConcurrentHashMap<>();
    /** 只用于查找，永不插入字典；每个 Owner/Lane/读取线程独享探针。 */
    private static final ThreadLocal<ClientLookup> CLIENT_LOOKUP = ThreadLocal.withInitial(ClientLookup::new);

    private static final class ClientLookup {
        /** 当前查找的原始身份键，哈希与字典中的 Long 一致。 */
        long value;
        @Override public int hashCode() { return Long.hashCode(value); }
        @Override public boolean equals(Object other) { return other instanceof Long key && key.longValue() == value; }
    }

    private static ClientLookup clientLookup(long key) {
        ClientLookup lookup = CLIENT_LOOKUP.get();
        lookup.value = key;
        return lookup;
    }


    /** Owner 按准入回执汇总身份分配次数，Lane 不争用全局版本写入。 */
    public void recordLaneClientAllocations(long count) {
        assertOwner();
        dictionaryVersion = Math.addExact(dictionaryVersion, count);
    }

    // Settlement Lanes read prepared keys while the owner prepares later sequences.
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    private final Map<Long, PositionEntry> positions = new ConcurrentHashMap<>();
    /** A Lane output retains its identity until ordered publication consumes that output. */
    private static final class PositionEntry extends java.util.concurrent.atomic.AtomicInteger {
        final PositionIdentity identity;
        boolean allocationRecorded; // Owner only; restored identities have already been recorded.
        volatile boolean identityRegistered;
        PositionEntry(PositionIdentity identity, boolean recorded) {
            this.identity = identity;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }
    }
    private final LongLongHashMap positionAllocationKeys = new LongLongHashMap();
    private final LongLongHashMap positionKeyAllocations = new LongLongHashMap();
    private int nextAssetId;
    private int nextSymbolId;
    private long nextPositionKey = 1;
    private long dictionaryVersion;
    private final RuntimeFactFrame.FactIdentitySlice liveFactIdentitySlice =
            new RuntimeFactFrame.FactIdentitySlice(
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, this);
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
        // Exact dictionary hits were validated at insertion; normalize only misses.
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        String normalized = AssetBalance.normalizeAsset(asset);
        Integer existing = assetIds.get(normalized);
        if (existing != null) return existing;
        int id = nextAssetId++;
        assetIds.put(normalized, id);
        assets = storeIdentity(assets, id, normalized);
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return id;
    }

    public Integer findAssetId(String asset) {
        assertOwner();
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        return assetIds.get(AssetBalance.normalizeAsset(asset));
    }

    public String asset(int assetId) {
        String[] current = assets;
        String asset = assetId < 0 || assetId >= current.length ? null : current[assetId];
        if (asset == null) throw new IllegalArgumentException("unknown runtime asset id: " + assetId);
        return asset;
    }

    public int symbolId(String symbol) {
        assertOwner();
        Integer known = symbolIds.get(symbol);
        if (known != null) return known;
        String normalized = OrderReservation.normalizeSymbol(symbol);
        Integer existing = symbolIds.get(normalized);
        if (existing != null) return existing;
        int id = nextSymbolId++;
        symbolIds.put(normalized, id);
        symbols = storeIdentity(symbols, id, normalized);
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return id;
    }

    public Integer findSymbolId(String symbol) {
        assertOwner();
        Integer known = symbolIds.get(symbol);
        if (known != null) return known;
        return symbolIds.get(OrderReservation.normalizeSymbol(symbol));
    }

    public String symbol(int symbolId) {
        return preparedSymbol(symbolId);
    }

    String preparedSymbol(int symbolId) {
        String[] current = symbols;
        String symbol = symbolId < 0 || symbolId >= current.length ? null : current[symbolId];
        if (symbol == null) throw new IllegalArgumentException("unknown runtime symbol id: " + symbolId);
        return symbol;
    }

    public long clientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return 0;
        long key = deterministicKey(userId, clientOrderId);
        boolean created = mutateClient(userId, () -> {
            ClientIdentityEntry collision = clients.putIfAbsent(key, new ClientIdentityEntry(userId, clientOrderId));
            if (collision != null && (collision.userId != userId || !collision.clientOrderId.equals(clientOrderId)))
                throw new IllegalStateException("deterministic client identity collision");
            return collision == null;
        });
        if (created) dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return key;
    }

    public PreparedClientKey prepareClientKey(long userId, String clientOrderId) {
        assertOwner();
        PreparedClientKey prepared = mutateClient(userId, () -> prepareClientKey(userId, clientOrderId, null));
        recordLaneClientAllocations(prepared.newIdentity() ? 1 : 0);
        return prepared;
    }

    PreparedClientKey prepareClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)) throw new IllegalStateException("client identity crossed account Lane");
        return prepareClientKey(userId, clientOrderId, lane);
    }

    private PreparedClientKey prepareClientKey(long userId, String clientOrderId, AccountLaneState lane) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return new PreparedClientKey(0, false);
        long key = deterministicKey(userId, clientOrderId);
        ClientIdentityEntry existing = clients.get(clientLookup(key));
        if (existing != null) {
            if (existing.userId != userId || !existing.clientOrderId.equals(clientOrderId)) {
                throw new IllegalStateException("deterministic client identity collision");
            }
            existing.references = Math.incrementExact(existing.references);
            return new PreparedClientKey(key, true);
        }
        ClientIdentityEntry collision = clients.putIfAbsent(key, new ClientIdentityEntry(userId, clientOrderId));
        if (collision != null) {
            if ((collision.userId != userId || !collision.clientOrderId.equals(clientOrderId))) {
                throw new IllegalStateException("deterministic client identity collision");
            }
            collision.references = Math.incrementExact(collision.references);
            return new PreparedClientKey(key, true);
        }
        if (lane != null) lane.clientIdentityAllocations = Math.incrementExact(lane.clientIdentityAllocations);
        return new PreparedClientKey(key, true, true);
    }

    private void releaseClientKeyReference(long userId, String clientOrderId, long clientKey) {
        ClientIdentityEntry existing = clients.get(clientLookup(clientKey));
        if (existing == null || existing.userId != userId
                || !existing.clientOrderId.equals(clientOrderId)) {
            throw new IllegalStateException("deterministic client identity collision");
        }
        if (--existing.references == 0) clients.remove(clientLookup(clientKey), existing);
    }

    public void rollbackPreparedClientKey(
            long userId, String clientOrderId, PreparedClientKey prepared) {
        assertOwner();
        if (userId <= 0 || clientOrderId == null || prepared == null || !prepared.allocated()) {
            throw new IllegalArgumentException("invalid prepared client key rollback");
        }
        mutateClient(userId, () -> { releaseClientKeyReference(userId, clientOrderId, prepared.key()); return null; });
    }

    void rollbackClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId, PreparedClientKey prepared) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)) throw new IllegalStateException("client identity crossed account Lane");
        if (prepared != null && prepared.allocated()) releaseClientKeyReference(userId, clientOrderId, prepared.key());
    }

    Long findPositionKeyInLane(AccountLaneState lane, long userId, String key) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)) throw new IllegalStateException("position identity crossed account Lane");
        return findPositionIdentity(userId, key);
    }

    public Long findClientKey(long userId, String clientOrderId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (clientOrderId == null || clientOrderId.isBlank()) return null;
        long key = deterministicKey(userId, clientOrderId);
        ClientIdentityEntry existing = clients.get(clientLookup(key));
        return existing != null && existing.userId == userId
                && existing.clientOrderId.equals(clientOrderId) ? key : null;
    }

    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        ClientIdentityEntry entry = clients.get(clientLookup(clientKey));
        if (entry == null || entry.userId != userId) {
            throw new IllegalArgumentException("unknown runtime client key: " + userId + '/' + clientKey);
        }
        return entry.clientOrderId;
    }

    public void releaseClientKey(long userId, long clientKey) {
        assertOwner();
        mutateClient(userId, () -> { releaseClientReference(userId, clientKey); return null; });
    }

    void releaseClientKeyInLane(AccountLaneState lane, long userId, long clientKey) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId))
            throw new IllegalStateException("client identity crossed account Lane");
        releaseClientReference(userId, clientKey);
    }

    private void releaseClientReference(long userId, long clientKey) {
        ClientIdentityEntry entry = clients.get(clientLookup(clientKey));
        if (entry == null || entry.userId != userId) return;
        if (--entry.references == 0) clients.remove(clientLookup(clientKey), entry);
    }

    public long positionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) {
            throw new IllegalArgumentException("invalid position identity");
        }
        Long existing = findPositionIdentity(userId, positionKey);
        if (existing != null) return existing;
        PositionIdentity identity = new PositionIdentity(userId, positionKey);
        long key = deterministicPositionKey(identity);
        PositionEntry entry = new PositionEntry(identity, false);
        PositionEntry collision = positions.putIfAbsent(key, entry);
        if (collision != null && !collision.identity.equals(identity)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        PositionEntry retained = collision == null ? entry : collision;
        ensurePositionIdentityRegistered(retained, key);
        recordPositionAllocation(key, retained);
        return key;
    }

    /** Register each position identity once; repeated Lane retains only observe the flag. */
    private void ensurePositionIdentityRegistered(PositionEntry entry, long key) {
        if (entry.identityRegistered) return;
        synchronized (entry) {
            if (entry.identityRegistered) return;
            positionKeys.putIfAbsent(entry.identity, key);
            entry.identityRegistered = true;
        }
    }

    /** Called once per position in a Lane's existing settlement delta, never per fill. */
    long retainPositionInLane(AccountLaneState lane, long userId, String name) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId() || name == null || name.isBlank())
            throw new IllegalArgumentException("position identity belongs to another Lane");
        long key = deterministicKey(userId, name);
        while (true) {
            PositionEntry entry = positions.get(key);
            if (entry == null) {
                PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), false);
                entry = positions.putIfAbsent(key, created);
                if (entry == null) entry = created;
            }
            if (entry.identity.userId() != userId || !entry.identity.positionKey().equals(name))
                throw new IllegalStateException("deterministic position identity collision");
            int uses = entry.get();
            if (uses < 0) { Thread.onSpinWait(); continue; }
            if (!entry.compareAndSet(uses, Math.incrementExact(uses))) continue;
            ensurePositionIdentityRegistered(entry, key);
            return key;
        }
    }

    /**
     * Retain a prepared derivative position without rebuilding the hedge-mode
     * name on every fill.  The name is materialized only if this is the first
     * publication of the identity; existing entries are checked directly from
     * their deterministic key and stored immutable name.
     */
    long retainPositionInLane(AccountLaneState lane, long userId, String symbol,
                              CorePositionSide side) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId()
                || symbol == null || symbol.isBlank() || side == null) {
            throw new IllegalArgumentException("position identity belongs to another Lane");
        }
        long key = positionIdentityKey(userId, symbol, side);
        while (true) {
            PositionEntry entry = positions.get(key);
            if (entry == null) {
                String name = side == CorePositionSide.NET ? symbol : symbol + ':' + side.name();
                PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), false);
                entry = positions.putIfAbsent(key, created);
                if (entry == null) entry = created;
            }
            if (entry.identity.userId() != userId
                    || !positionNameEquals(entry.identity.positionKey(), symbol, side)) {
                throw new IllegalStateException("deterministic position identity collision");
            }
            int uses = entry.get();
            if (uses < 0) { Thread.onSpinWait(); continue; }
            if (!entry.compareAndSet(uses, Math.incrementExact(uses))) continue;
            ensurePositionIdentityRegistered(entry, key);
            return key;
        }
    }

    void releasePublishedPosition(long key) {
        assertOwner();
        PositionEntry entry = positions.get(key);
        if (entry == null || entry.get() <= 0)
            throw new IllegalStateException("position publication has no retained identity");
        recordPositionAllocation(key, entry);
        if (entry.decrementAndGet() < 0) throw new IllegalStateException("position identity released twice");
    }

    private void recordPositionAllocation(long key, PositionEntry entry) {
        if (entry.allocationRecorded) return;
        trackAllocation(positionAllocationKeys, positionKeyAllocations, nextPositionKey++, key);
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        entry.allocationRecorded = true;
    }

    public Long findPositionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) return null;
        return findPositionIdentity(userId, positionKey);
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
            long key = positionAllocationKeys.removeKeyIfAbsent(allocation, 0);
            if (key == 0) continue;
            if (positionKeyAllocations.removeKeyIfAbsent(key, allocation) != allocation) {
                throw new IllegalStateException("position identity allocation index is inconsistent");
            }
            PositionEntry entry = positions.get(key);
            if (entry != null && !entry.compareAndSet(0, -1))
                throw new IllegalStateException("cannot roll back a Lane-owned position identity");
            PositionIdentity identity = entry == null ? null : entry.identity;
            if (identity == null || !Long.valueOf(key).equals(positionKeys.remove(identity))) {
                throw new IllegalStateException("position identity checkpoint is inconsistent");
            }
            entry.identityRegistered = false;
            positions.remove(key, entry);
        }
    }

    private static long deterministicPositionKey(PositionIdentity identity) {
        return deterministicKey(identity.userId(), identity.positionKey());
    }

    static long positionIdentityKey(long userId, String name) { return deterministicKey(userId, name); }

    static long positionIdentityKey(long userId, String symbol, CorePositionSide side) {
        if (side == CorePositionSide.NET) return deterministicKey(userId, symbol);
        long hash = 0xcbf29ce484222325L;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
            hash = hashByte(hash, (int) (userId >>> shift & 0xffL));
        hash = hashUtf8(hash, symbol);
        hash = hashByte(hash, ':');
        String sideName = side.name();
        for (int i = 0; i < sideName.length(); i++) hash = hashByte(hash, sideName.charAt(i));
        hash &= Long.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    long preparedPositionKey(long userId, String positionKey) {
        Long key = findPositionIdentity(userId, positionKey);
        if (key == null) throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    long preparedPositionKey(long userId, String symbol, CorePositionSide side) {
        long key = positionIdentityKey(userId, symbol, side);
        PositionEntry entry = positions.get(key);
        if (entry == null || entry.identity.userId() != userId
                || !positionNameEquals(entry.identity.positionKey(), symbol, side)) {
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        }
        return key;
    }

    private static boolean positionNameEquals(String actual, String symbol, CorePositionSide side) {
        if (side == CorePositionSide.NET) return actual.equals(symbol);
        String sideName = side.name();
        int prefixLength = symbol.length();
        if (actual.length() != prefixLength + 1 + sideName.length()
                || actual.charAt(prefixLength) != ':') return false;
        if (!actual.regionMatches(0, symbol, 0, prefixLength)) return false;
        return actual.regionMatches(prefixLength + 1, sideName, 0, sideName.length());
    }

    public String positionKey(long userId, long positionKey) {
        PositionEntry entry = positions.get(positionKey);
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    public PositionIdentity positionIdentity(long positionKey) {
        PositionEntry entry = positions.get(positionKey);
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null) {
            throw new IllegalArgumentException("unknown runtime position key: " + positionKey);
        }
        return identity;
    }

    public long dictionaryVersion() {
        return dictionaryVersion;
    }

    public int clientIdentityCount() {
        assertOwner();
        return clients.size();
    }

    RuntimeFactFrame.FactIdentitySlice liveFactIdentitySlice() {
        return liveFactIdentitySlice;
    }

    public void releasePositionKey(long positionKey) {
        assertOwner();
        PositionEntry entry = positions.get(positionKey);
        if (entry == null || !entry.compareAndSet(0, -1)) return;
        // Remove the forward entry before permitting another generation to acquire this key.
        positionKeys.remove(entry.identity, positionKey);
        entry.identityRegistered = false;
        positions.remove(positionKey, entry);
        removeAllocation(positionAllocationKeys, positionKeyAllocations, positionKey);
    }

    public Snapshot snapshot() {
        assertOwner();
        Map<ClientIdentity, Long> clientKeys = new HashMap<>(clientIdentityCount());
        clients.forEach((key, entry) -> clientKeys.put(new ClientIdentity(entry.userId, entry.clientOrderId), key));
        return new Snapshot(assetIds, symbolIds, clientKeys, positionKeys,
                nextAssetId, nextSymbolId, clientKeys.size() + 1L, positionKeys.size() + 1L);
    }

    private static long deterministicKey(long userId, String value) {
        long hash = 0xcbf29ce484222325L;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ (userId >>> shift & 0xffL)) * 0x100000001b3L;
        }
        hash = hashUtf8(hash, value);
        long key = hash & Long.MAX_VALUE;
        return key == 0 ? 1 : key;
    }

    private static long hashUtf8(long hash, String value) {
        // 按 Java UTF-8 编码逐字节计算，避免为每次身份查询创建 byte[]。
        // 非法代理项与 String.getBytes(UTF_8) 一致，编码为 '?'，保持历史身份键不变。
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) hash = hashByte(hash, c);
            else if (c < 0x800) {
                hash = hashByte(hash, 0xc0 | (c >>> 6));
                hash = hashByte(hash, 0x80 | (c & 0x3f));
            } else if (!Character.isSurrogate(c)) {
                hash = hashByte(hash, 0xe0 | (c >>> 12));
                hash = hashByte(hash, 0x80 | ((c >>> 6) & 0x3f));
                hash = hashByte(hash, 0x80 | (c & 0x3f));
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int point = Character.toCodePoint(c, value.charAt(++i));
                hash = hashByte(hash, 0xf0 | (point >>> 18));
                hash = hashByte(hash, 0x80 | ((point >>> 12) & 0x3f));
                hash = hashByte(hash, 0x80 | ((point >>> 6) & 0x3f));
                hash = hashByte(hash, 0x80 | (point & 0x3f));
            } else hash = hashByte(hash, '?');
        }
        return hash;
    }

    private static long hashByte(long hash, int value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static String[] storeIdentity(String[] values, int id, String value) {
        String[] target = values;
        if (id >= target.length) {
            int capacity = target.length;
            while (capacity <= id) capacity = Math.multiplyExact(capacity, 2);
            target = java.util.Arrays.copyOf(target, capacity);
        }
        target[id] = value;
        return target;
    }

    private static void trackAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                        long allocation, long key) {
        if (allocations.containsKey(allocation) || allocationsByKey.containsKey(key)) {
            throw new IllegalStateException("runtime identity allocation collision");
        }
        allocations.put(allocation, key);
        allocationsByKey.put(key, allocation);
    }

    private static void removeAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                         long key) {
        long allocation = allocationsByKey.removeKeyIfAbsent(key, 0);
        if (allocation == 0) return;
        if (allocations.removeKeyIfAbsent(allocation, key) != key) {
            throw new IllegalStateException("runtime identity allocation index is inconsistent");
        }
    }

    public static RuntimeIdentityRegistry restore(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("identity snapshot is required");
        RuntimeIdentityRegistry registry = new RuntimeIdentityRegistry();
        snapshot.assetIds().forEach((name, id) -> {
            registry.assetIds.put(name, id);
            registry.assets = storeIdentity(registry.assets, id, name);
        });
        snapshot.symbolIds().forEach((name, id) -> {
            registry.symbolIds.put(name, id);
            registry.symbols = storeIdentity(registry.symbols, id, name);
        });
        snapshot.clientKeys().forEach((identity, key) -> {
            registry.clients.put(key, new ClientIdentityEntry(identity.userId(), identity.clientOrderId()));
        });
        snapshot.positionKeys().forEach((identity, key) -> {
            registry.positionKeys.put(identity, key);
            registry.positions.put(key, new PositionEntry(identity, true));
        });
        registry.nextAssetId = snapshot.nextAssetId();
        registry.nextSymbolId = snapshot.nextSymbolId();
        registry.nextPositionKey = snapshot.nextPositionKey();
        registry.dictionaryVersion = Math.addExact(
                Math.addExact(snapshot.nextAssetId(), snapshot.nextSymbolId()),
                Math.addExact(snapshot.nextClientKey() - 1, snapshot.nextPositionKey() - 1));
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

    public record PreparedClientKey(long key, boolean allocated, boolean newIdentity) {
        public PreparedClientKey(long key, boolean allocated) { this(key, allocated, false); }
        public PreparedClientKey {
            if (key < 0 || (key == 0 && allocated) || (newIdentity && !allocated)) {
                throw new IllegalArgumentException("invalid prepared client key");
            }
        }
    }

    /** The dictionary key already carries the hash; no duplicate key or identity wrapper per order. */
    private static final class ClientIdentityEntry {
        private final long userId;
        private final String clientOrderId;
        private long references = 1; // Account Lane only; never read or decremented by Owner.

        private ClientIdentityEntry(long userId, String clientOrderId) {
            this.userId = userId;
            this.clientOrderId = clientOrderId;
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
