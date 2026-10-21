package com.surprising.aeron.service.state.identity;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.LaneClientIdentities;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.order.OrderRuntime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

public final class RuntimeIdentityRegistry {
    private final LookupScratch ownerLookupScratch = new LookupScratch();

    /** Owner and each Account Lane reuse separate probes; other query readers borrow local probes. */
    private LookupScratch lookupScratch() {
        if (Thread.currentThread() == owner) return ownerLookupScratch;
        AccountLaneState lane = clientRuntime == null ? null : clientRuntime.currentLaneCommandScope();
        return lane == null ? new LookupScratch() : lane.identityLookupScratch();
    }

    public static final class LookupScratch {
        private final PositionLookup position = new PositionLookup();
        private final PositionMapLookup positionMap = new PositionMapLookup();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionEntry> positionEntries =
                new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    }

    private Long findPositionIdentity(long userId, String key) {
        PositionLookup lookup = lookupScratch().position;
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

    // Asset/instrumentId forward and allocation indexes are owner-only. Monotonic asset/instrumentId
    // dictionaries use volatile array publication; releasable client/position
    // identities remain concurrent for asynchronous Core Fact materializers.
    private final Map<String, Integer> assetIds = new HashMap<>();
    private volatile String[] assets = new String[16];
    private final org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap symbolIds = new org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap();
    private volatile String[] symbols = new String[16];
    /** 校验异步身份准备确实在所属账户 Lane 执行。 */
    private LaneTopology clientTopology = LaneTopology.configured(false);
    private TradingRuntimeState clientRuntime;

    /** Only cold standalone construction has unassigned identities; binding transfers and releases it. */
    private org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<LaneClientIdentities> unboundClients;

    public void bindClientLanes(TradingRuntimeState runtime) {
        assertOwner();
        if (clientRuntime == runtime) return;
        if (runtime.accountLanesStarted() || clientRuntime != null && clientRuntime.accountLanesStarted())
            throw new IllegalStateException("client identities already belong to a runtime");
        // Copy into the new, cold runtime before releasing any source authority. A failed import
        // leaves the old runtime intact; the incomplete new runtime is never published.
        if (clientRuntime != null) {
            for (int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
lane.clientIdentities().copyTo(runtime);
        }
        } else if (unboundClients != null) unboundClients.forEachValue(table -> table.copyTo(runtime));
        if (clientRuntime != null) {
            for (int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
lane.clientIdentities().clear();
        }
        }
        if (unboundClients != null) { unboundClients.clear(); unboundClients = null; }
        clientTopology = runtime.topology();
        clientRuntime = runtime;
    }

    private AccountLaneState clientLane(long userId) {
        if (clientRuntime == null) throw new IllegalStateException("client identity requires a runtime");
        return clientRuntime.accountLaneForUserReadOnly(userId);
    }

    private LaneClientIdentities clientTable(long userId) {
        if (clientRuntime != null) return clientLane(userId).clientIdentities();
        assertOwner();
        if (unboundClients == null)
            unboundClients = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
        LaneClientIdentities table = unboundClients.get(userId);
        if (table == null) { table = new LaneClientIdentities(null); unboundClients.put(userId, table); }
        return table;
    }

    private LaneClientIdentities clientTableReadOnly(long userId) {
        if (clientRuntime != null) return clientLane(userId).clientIdentities();
        var clients = unboundClients;
        return clients == null ? null : clients.get(userId);
    }

    private LaneClientIdentities clientTable(AccountLaneState lane, long userId) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || (clientRuntime != null && clientRuntime.accountLane(lane.laneId()) != lane))
            throw new IllegalStateException("client identity crossed account Lane");
        if (clientRuntime != null) return lane.clientIdentities();
        return clientTable(userId);
    }

    public long prepareClientKeyInCurrentLane(long userId, String name) {
        return prepareClientKeyInLane(clientLane(userId), userId, name);
    }

    public void rollbackClientKeyInCurrentLane(long userId, String name, long key) {
        rollbackClientKeyInLane(clientLane(userId), userId, name, key);
    }

    /** Owner 按准入回执汇总身份分配次数，Lane 不争用全局版本写入。 */
    public void recordLaneClientAllocations(long count) {
        assertOwner();
        dictionaryVersion = Math.addExact(dictionaryVersion, count);
    }

    // Settlement Lanes read prepared keys while the owner prepares later sequences.
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    /** Position entries are read on every derivative fill; probe by primitive key without boxing. */
    private final Map<PositionMapKey, PositionEntry> positions = new ConcurrentHashMap<>();

    private static final class PositionMapKey {
        final long value;
        PositionMapKey(long value) { this.value = value; }
        @Override public int hashCode() { return Long.hashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey key && value == key.value
                    || other instanceof PositionMapLookup lookup && value == lookup.value;
        }
    }

    private static final class PositionMapLookup {
        long value;
        @Override public int hashCode() { return Long.hashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey key && value == key.value;
        }
    }

    private PositionEntry positionEntry(long key) {
        return positionEntry(lookupScratch(), key);
    }

    private PositionEntry positionEntry(AccountLaneState lane, long key) {
        return positionEntry(lane.identityLookupScratch(), key);
    }

    private PositionEntry positionEntry(LookupScratch scratch, long key) {
        PositionEntry cached = scratch.positionEntries.get(key);
        if (cached != null && cached.get() >= 0 && cached.identityRegistered) {
            return cached;
        }
        PositionMapLookup lookup = scratch.positionMap;
        lookup.value = key;
        PositionEntry entry = positions.get(lookup);
        if (entry != null && entry.get() >= 0 && entry.identityRegistered) {
            scratch.positionEntries.put(key, entry);
        } else if (cached != null) {
            scratch.positionEntries.remove(key);
        }
        return entry;
    }

    /** A Lane output retains its identity until ordered publication consumes that output. */
    private static final class PositionEntry extends java.util.concurrent.atomic.AtomicInteger {
        final PositionIdentity identity;
        String verifiedInstrumentId;
        CorePositionSide verifiedSide;
        boolean allocationRecorded; // Owner only; restored identities have already been recorded.
        volatile boolean identityRegistered;
        PositionEntry(PositionIdentity identity, boolean recorded) {
            this.identity = identity;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        PositionEntry(PositionIdentity identity, String instrumentId, CorePositionSide side, boolean recorded) {
            this.identity = identity;
            this.verifiedInstrumentId = instrumentId;
            this.verifiedSide = side;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        boolean matches(long userId, String instrumentId, CorePositionSide side) {
            if (identity.userId() != userId) return false;
            if (verifiedSide == side && verifiedInstrumentId != null && verifiedInstrumentId.equals(instrumentId)) {
                return true;
            }
            if (!positionNameEquals(identity.positionKey(), instrumentId, side)) return false;
            verifiedInstrumentId = instrumentId;
            verifiedSide = side;
            return true;
        }
    }
    private final LongLongHashMap positionAllocationKeys = new LongLongHashMap();
    private final LongLongHashMap positionKeyAllocations = new LongLongHashMap();
    private int nextAssetId;
    private int nextSymbolId;
    private long nextPositionKey = 1;
    private long dictionaryVersion;
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

    public int symbolId(String instrumentId) {
        return symbolId(com.surprising.product.api.InstrumentIds.parse(instrumentId));
    }

    public int symbolId(int instrumentId) {
        assertOwner();
        if (instrumentId <= 0) throw new IllegalArgumentException("positive instrumentId is required");
        int existing = symbolIds.getIfAbsent(instrumentId, -1);
        if (existing >= 0) return existing;
        int id = nextSymbolId++;
        symbolIds.put(instrumentId, id);
        symbols = storeIdentity(symbols, id, Integer.toString(instrumentId));
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return id;
    }

    public Integer findSymbolId(String instrumentId) {
        int id = findSymbolIdValue(com.surprising.product.api.InstrumentIds.parse(instrumentId));
        return id < 0 ? null : id;
    }

    /** Dense runtime ID; -1 denotes an unregistered permanent ID, since runtime ID 0 is valid. */
    public int findSymbolIdValue(int instrumentId) {
        assertOwner();
        return symbolIds.getIfAbsent(instrumentId, -1);
    }

    public String instrumentId(int symbolId) {
        return preparedSymbol(symbolId);
    }

    public String preparedSymbol(int symbolId) {
        String[] current = symbols;
        String instrumentId = symbolId < 0 || symbolId >= current.length ? null : current[symbolId];
        if (instrumentId == null) throw new IllegalArgumentException("unknown runtime instrumentId id: " + symbolId);
        return instrumentId;
    }

    /** Cold state projection is idempotent; it does not retain an existing order alias twice. */
    public long clientKey(long userId, String clientOrderId) {
        assertOwner();
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        long prepared = clientTable(userId).prepare(key, userId, clientOrderId, false);
        if (prepared < 0) dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return key;
    }

    public long prepareClientKey(long userId, String clientOrderId) {
        assertOwner();
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        long prepared = clientTable(userId).prepare(key, userId, clientOrderId, true);
        if (prepared < 0) recordLaneClientAllocations(1);
        return key;
    }

    public long prepareClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId) {
        LaneClientIdentities table = clientTable(lane, userId);
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        long prepared = table.prepare(key, userId, clientOrderId, true);
        if (prepared < 0) lane.clientIdentityAllocations(Math.incrementExact(lane.clientIdentityAllocations()));
        // The allocation bit is returned in the sign; the key stored in Lane state stays positive.
        return prepared;
    }

    private static long clientKeyOrZero(long userId, String name) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return name == null || name.isBlank() ? 0 : deterministicKey(userId, name);
    }

    public void rollbackPreparedClientKey(long userId, String clientOrderId, long preparedKey) {
        assertOwner();
        if (userId <= 0 || clientOrderId == null || preparedKey == 0)
            throw new IllegalArgumentException("invalid prepared client key rollback");
        clientTable(userId).rollback(clientKeyValue(preparedKey), userId, clientOrderId);
    }

    public void rollbackClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId, long preparedKey) {
        LaneClientIdentities table = clientTable(lane, userId);
        if (preparedKey != 0) table.rollback(clientKeyValue(preparedKey), userId, clientOrderId);
    }

    /** Prepared keys encode a newly allocated identity in the sign bit; map keys remain positive. */
    public static long clientKeyValue(long preparedKey) {
        return preparedKey < 0 ? Math.negateExact(preparedKey) : preparedKey;
    }

    public static boolean newClientIdentity(long preparedKey) { return preparedKey < 0; }

    Long findPositionKeyInLane(AccountLaneState lane, long userId, String key) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)) throw new IllegalStateException("position identity crossed account Lane");
        PositionLookup lookup = lane.identityLookupScratch().position;
        lookup.userId = userId;
        lookup.key = key;
        try { return positionKeys.get(lookup); }
        finally { lookup.key = null; }
    }

    long findPositionKeyValueInLane(AccountLaneState lane, long userId, String key) {
        Long value = findPositionKeyInLane(lane, userId, key);
        return value == null ? 0 : value;
    }

    public long findPositionKeyValueInLane(AccountLaneState lane, long userId,
                                    CoreInstrument instrument, CorePositionSide side) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || instrument == null || side == null) {
            throw new IllegalStateException("position identity crossed account Lane");
        }
        long key = positionIdentityKey(userId, instrumentId(instrument), side);
        PositionEntry entry = positionEntry(lane, key);
        return entry != null && entry.matches(userId, instrument.instrumentId(), side) ? key : 0;
    }

    private static String instrumentId(CoreInstrument instrument) {
        return instrument.instrumentId();
    }

    public Long findClientKey(long userId, String clientOrderId) {
        long key = findClientKeyValue(userId, clientOrderId);
        return key == 0 ? null : key;
    }

    /** The caller must own the user's Lane, or hold the explicit cold read fence. */
    public long findClientKeyValue(long userId, String clientOrderId) {
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        LaneClientIdentities table = clientTableReadOnly(userId);
        return table == null ? 0 : table.find(key, userId, clientOrderId);
    }

    public long findClientKeyValueInLane(AccountLaneState lane, long userId, String clientOrderId) {
        LaneClientIdentities table = clientTable(lane, userId);
        long key = clientKeyOrZero(userId, clientOrderId);
        return key == 0 ? 0 : table.find(key, userId, clientOrderId);
    }

    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        LaneClientIdentities table = clientTableReadOnly(userId);
        return table == null ? null : table.name(clientKey, userId);
    }

    public void releaseClientKey(long userId, long clientKey) {
        assertOwner();
        clientTable(userId).release(clientKey, userId);
    }

    public void releaseClientKeyInLane(AccountLaneState lane, long userId, long clientKey) {
        clientTable(lane, userId).release(clientKey, userId);
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
        PositionEntry collision = positions.putIfAbsent(new PositionMapKey(key), entry);
        if (collision != null && !collision.identity.equals(identity)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        PositionEntry retained = collision == null ? entry : collision;
        ensurePositionIdentityRegistered(retained, key);
        recordPositionAllocation(key, retained);
        ownerLookupScratch.positionEntries.put(key, retained);
        return key;
    }

    public long positionKey(long userId, CoreInstrument instrument, CorePositionSide side) {
        if (instrument == null) {
            throw new IllegalArgumentException("invalid position identity");
        }
        return positionKey(userId, instrument.instrumentId(), side);
    }

    public long positionKey(long userId, String instrumentId, CorePositionSide side) {
        assertOwner();
        if (userId <= 0 || instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("invalid position identity");
        }
        long key = positionIdentityKey(userId, instrumentId, side);
        PositionEntry existing = positionEntry(key);
        if (existing != null) {
            if (!existing.matches(userId, instrumentId, side)) {
                throw new IllegalStateException("deterministic position identity collision");
            }
            return key;
        }
        String name = side == CorePositionSide.NET ? instrumentId : instrumentId + ':' + side.name();
        PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), instrumentId, side, false);
        PositionEntry collision = positions.putIfAbsent(new PositionMapKey(key), created);
        if (collision != null && !collision.matches(userId, instrumentId, side)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        PositionEntry retained = collision == null ? created : collision;
        ensurePositionIdentityRegistered(retained, key);
        recordPositionAllocation(key, retained);
        ownerLookupScratch.positionEntries.put(key, retained);
        return key;
    }

    /** Register each position identity once; repeated Lane retains only observe the flag. */
    private void ensurePositionIdentityRegistered(PositionEntry entry, long key) {
        if (entry.identityRegistered) return;
        positionKeys.putIfAbsent(entry.identity, key);
        entry.identityRegistered = true;
    }

    /** Called once per position in a Lane's existing settlement delta, never per fill. */
    public long retainPositionInLane(AccountLaneState lane, long userId, String name) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId() || name == null || name.isBlank())
            throw new IllegalArgumentException("position identity belongs to another Lane");
        long key = deterministicKey(userId, name);
        while (true) {
            PositionEntry entry = positionEntry(lane, key);
            if (entry == null) {
                PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), false);
                entry = positions.putIfAbsent(new PositionMapKey(key), created);
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
    public long retainPositionInLane(AccountLaneState lane, long userId, String instrumentId,
                              CorePositionSide side) {
        if (instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("position identity belongs to another Lane");
        }
        return retainPositionInLane(lane, positionIdentityKey(userId, instrumentId, side),
                userId, instrumentId, side);
    }

    public long retainPositionInLane(AccountLaneState lane, long key, long userId, String instrumentId,
                              CorePositionSide side) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId()
                || instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("position identity belongs to another Lane");
        }
        while (true) {
            PositionEntry entry = positionEntry(lane, key);
            if (entry == null) {
                String name = side == CorePositionSide.NET ? instrumentId : instrumentId + ':' + side.name();
                PositionEntry created = new PositionEntry(
                        new PositionIdentity(userId, name), instrumentId, side, false);
                entry = positions.putIfAbsent(new PositionMapKey(key), created);
                if (entry == null) entry = created;
            }
            if (!entry.matches(userId, instrumentId, side)) {
                throw new IllegalStateException("deterministic position identity collision");
            }
            int uses = entry.get();
            if (uses < 0) { Thread.onSpinWait(); continue; }
            if (!entry.compareAndSet(uses, Math.incrementExact(uses))) continue;
            ensurePositionIdentityRegistered(entry, key);
            lane.identityLookupScratch().positionEntries.put(key, entry);
            return key;
        }
    }

    public void releasePublishedPosition(long key) {
        assertOwner();
        PositionEntry entry = positionEntry(key);
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

    long findPositionKeyValue(long userId, String positionKey) {
        Long value = findPositionKey(userId, positionKey);
        return value == null ? 0 : value;
    }

    public long findPositionKeyValue(long userId, CoreInstrument instrument, CorePositionSide side) {
        assertOwner();
        if (userId <= 0 || instrument == null || side == null) return 0;
        return findPositionKeyValue(userId, instrument.instrumentId(), side);
    }

    private long findPositionKeyValue(long userId, String instrumentId, CorePositionSide side) {
        long key = positionIdentityKey(userId, instrumentId, side);
        PositionEntry entry = positionEntry(key);
        return entry != null && entry.matches(userId, instrumentId, side) ? key : 0;
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
            PositionEntry entry = positionEntry(key);
            if (entry != null && !entry.compareAndSet(0, -1))
                throw new IllegalStateException("cannot roll back a Lane-owned position identity");
            PositionIdentity identity = entry == null ? null : entry.identity;
            if (identity == null || !Long.valueOf(key).equals(positionKeys.remove(identity))) {
                throw new IllegalStateException("position identity checkpoint is inconsistent");
            }
            entry.identityRegistered = false;
            ownerLookupScratch.positionEntries.remove(key);
            positions.remove(new PositionMapKey(key), entry);
        }
    }

    private static long deterministicPositionKey(PositionIdentity identity) {
        return deterministicKey(identity.userId(), identity.positionKey());
    }

    static long positionIdentityKey(long userId, String name) { return deterministicKey(userId, name); }

    public static long positionIdentityKey(long userId, String instrumentId, CorePositionSide side) {
        if (side == CorePositionSide.NET) return deterministicKey(userId, instrumentId);
        long hash = 0xcbf29ce484222325L;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
            hash = hashByte(hash, (int) (userId >>> shift & 0xffL));
        hash = hashUtf8(hash, instrumentId);
        hash = hashByte(hash, ':');
        String sideName = side.name();
        for (int i = 0; i < sideName.length(); i++) hash = hashByte(hash, sideName.charAt(i));
        hash &= Long.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    public long preparedPositionKey(long userId, String positionKey) {
        Long key = findPositionIdentity(userId, positionKey);
        if (key == null) throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    public long preparedPositionKey(long userId, String instrumentId, CorePositionSide side) {
        long key = positionIdentityKey(userId, instrumentId, side);
        PositionEntry entry = positionEntry(key);
        if (entry == null || !entry.matches(userId, instrumentId, side)) {
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        }
        return key;
    }

    public long preparedPositionKey(AccountLaneState lane, OrderRuntime order) {
        lane.assertOwner();
        long userId = order.userId();
        if (lane.laneId() != clientTopology.accountLaneId(userId))
            throw new IllegalStateException("position identity crossed account Lane");
        long key = order.positionIdentityKey();
        PositionEntry entry = positionEntry(lane, key);
        if (entry == null || !entry.matches(userId, order.instrument().instrumentId(), order.positionSide()))
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    public long preparedPositionKey(AccountLaneState lane, long userId, String instrumentId, CorePositionSide side) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId))
            throw new IllegalStateException("position identity crossed account Lane");
        long key = positionIdentityKey(userId, instrumentId, side);
        PositionEntry entry = positionEntry(lane, key);
        if (entry == null || !entry.matches(userId, instrumentId, side))
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    private static boolean positionNameEquals(String actual, String instrumentId, CorePositionSide side) {
        if (side == CorePositionSide.NET) return actual.equals(instrumentId);
        String sideName = side.name();
        int prefixLength = instrumentId.length();
        if (actual.length() != prefixLength + 1 + sideName.length()
                || actual.charAt(prefixLength) != ':') return false;
        if (!actual.regionMatches(0, instrumentId, 0, prefixLength)) return false;
        return actual.regionMatches(prefixLength + 1, sideName, 0, sideName.length());
    }

    public String positionKey(long userId, long positionKey) {
        PositionEntry entry = positionEntry(positionKey);
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    public PositionIdentity positionIdentity(long positionKey) {
        PositionEntry entry = positionEntry(positionKey);
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
        int count = 0;
        if (clientRuntime != null) {
            for (int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
count = Math.addExact(count, lane.clientIdentities().publishedCount());
        }
        } else if (unboundClients != null) {
            var iterator = unboundClients.values().iterator();
            while (iterator.hasNext()) count = Math.addExact(count, iterator.next().publishedCount());
        }
        return count;
    }

    public void releasePositionKey(long positionKey) {
        assertOwner();
        PositionEntry entry = positionEntry(positionKey);
        if (entry == null || !entry.compareAndSet(0, -1)) return;
        // Remove the forward entry before permitting another generation to acquire this key.
        positionKeys.remove(entry.identity, positionKey);
        entry.identityRegistered = false;
        ownerLookupScratch.positionEntries.remove(positionKey);
        positions.remove(new PositionMapKey(positionKey), entry);
        removeAllocation(positionAllocationKeys, positionKeyAllocations, positionKey);
    }

    public Snapshot snapshot() {
        assertOwner();
        Map<ClientIdentity, Long> clientKeys = new HashMap<>(clientIdentityCount());
        if (clientRuntime != null) {
            for (int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
lane.clientIdentities().exportTo(clientKeys);
        }
        } else if (unboundClients != null) unboundClients.forEachValue(table -> table.exportTo(clientKeys));
        Map<String, Integer> exportedSymbols = new HashMap<>(symbolIds.size());
        symbolIds.forEachKeyValue((permanentId, runtimeId) -> exportedSymbols.put(symbols[runtimeId], runtimeId));
        return new Snapshot(assetIds, exportedSymbols, clientKeys, positionKeys,
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
            registry.symbolIds.put(com.surprising.product.api.InstrumentIds.parse(name), id);
            registry.symbols = storeIdentity(registry.symbols, id, name);
        });
        snapshot.clientKeys().forEach((identity, key) -> {
            registry.clientTable(identity.userId()).importIdentity(key, identity.userId(), identity.clientOrderId());
        });
        snapshot.positionKeys().forEach((identity, key) -> {
            registry.positionKeys.put(identity, key);
            registry.positions.put(new PositionMapKey(key), new PositionEntry(identity, true));
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
            assetIds = Collections.unmodifiableMap(assetIds instanceof java.util.NavigableMap<?, ?> ? assetIds : new java.util.LinkedHashMap<>(assetIds));
            symbolIds = Collections.unmodifiableMap(symbolIds instanceof java.util.NavigableMap<?, ?> ? symbolIds : new java.util.LinkedHashMap<>(symbolIds));
            clientKeys = Collections.unmodifiableMap(clientKeys);
            positionKeys = Collections.unmodifiableMap(positionKeys);
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
