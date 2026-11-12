package com.surprising.aeron.service.core.identity;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.ClientOrderIdRegistry;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

/** 运行期身份表：把资产和永久数字标的映射成内部稠密编号，并保存用户范围的客户端单号、持仓名与确定性长整数键。资产、标的和分配记录由 Owner 写。 */
public final class IdIndex {
    /** Owner 独占的持仓查找探针和缓存，不与工作 Lane 共用。 */
    private final LookupScratch ownerLookupScratch = new LookupScratch();

    /** 按调用者选择查找探针：Owner 和当前账户命令作用域各复用自己的实例，其他读取者每次新建局部探针，避免并发改同一探针。 */
    private LookupScratch lookupScratch() {
        if (Thread.currentThread() == owner) return ownerLookupScratch;
        AccountPartition lane = clientRuntime == null ? null : clientRuntime.currentLaneCommandScope();
        return lane == null ? new LookupScratch() : lane.identityLookupScratch();
    }

    /** 单个 Owner 或账户 Lane 独占的查找暂存：复用正向、反向探针和已登记持仓项缓存。 */
    public static final class LookupScratch {
        private final PositionLookup position = new PositionLookup();
        /** 按确定性长整数键查询反向表的可变探针。 */
        private final PositionMapLookup positionMap = new PositionMapLookup();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionEntry> positionEntries =
                new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    }

    /** 借用当前线程的持仓名探针查正向表，结束时清掉临时文本引用。 */
    private Long findPositionIdentity(long userId, String key) {
        PositionLookup lookup = lookupScratch().position;
        lookup.userId = userId;
        lookup.key = key;
        try { return positionKeys.get(lookup); }
        finally { lookup.key = null; }
    }

    /** 只在一次查询中填入用户、持仓名的可变探针，不作为长期字典键。 */
    private static final class PositionLookup {
        long userId;
        String key;
        /** 按用户及持仓名计算与 PositionIdentity 相同的哈希，供可变探针查询不可变身份键。 */
        @Override public int hashCode() { return 31 * Long.hashCode(userId) + key.hashCode(); }
        /** 只和存储用的 PositionIdentity 比较用户、持仓名。 */
        @Override public boolean equals(Object other) {
            return other instanceof PositionIdentity identity
                    && userId == identity.userId() && key.equals(identity.positionKey());
        }
    }

    // 资产、标的正向字典及分配记录只由 Owner 修改。
    // 只增身份的反向数组通过 volatile 发布。
    // 可释放的身份另按账户所有权及并发表规则处理，供异步事实发布读取。
    /** Owner 独占的规范资产文本到内部编号字典。 */
    private final Map<String, Integer> assetIds = new HashMap<>();
    /** 通过 volatile 引用发布的资产反向数组，容量足够时写入后重新发布同一数组。 */
    private volatile String[] assets = new String[16];
    /** Owner 独占的永久正整数标的 ID 到内部稠密编号的映射。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap symbolIds = new org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap();
    private volatile String[] symbols = new String[16];
    private LaneTopology clientTopology;

    /** 独立工具入口使用统一配置绑定的账户拓扑。 */
    public IdIndex() {
        this(CoreConfiguration.standaloneLaneTopology());
    }

    /** 接收运行时已经确定的账户拓扑，避免身份表重新读取外部配置。 */
    public IdIndex(LaneTopology topology) {
        clientTopology = java.util.Objects.requireNonNull(topology, "topology");
    }
    private AccountBook clientRuntime;

    /** 首次装载前绑定唯一账户运行时。 */
    public void bindClientLanes(AccountBook accountBook) {
        assertOwner();
        java.util.Objects.requireNonNull(accountBook, "runtime");
        if (clientRuntime != null || accountBook.accountLanesStarted())
            throw new IllegalStateException("client identities require one passive runtime binding");
        clientTopology = accountBook.topology();
        clientRuntime = accountBook;
    }

    /** 取得引用本身不授予跨线程访问权限。 */
    private AccountPartition clientLane(long userId) {
        if (clientRuntime == null) throw new IllegalStateException("client identity requires a runtime");
        return clientRuntime.accountLaneForUserReadOnly(userId);
    }

    private ClientOrderIdRegistry clientTable(long userId) {
        return clientLane(userId).clientIdentities();
    }

    private ClientOrderIdRegistry clientTable(AccountPartition lane, long userId) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || clientRuntime == null || clientRuntime.accountLane(lane.laneId()) != lane)
            throw new IllegalStateException("client identity crossed account Lane");
        return lane.clientIdentities();
    }

    /** 定位用户所属 Lane，按 Lane 准入规则准备并保留客户端身份。 */
    public long prepareClientKeyInCurrentLane(long userId, String name) {
        return prepareClientKeyInLane(clientLane(userId), userId, name);
    }

    /** 定位用户所属 Lane，撤销该次准备所持的客户端身份引用。 */
    public void rollbackClientKeyInCurrentLane(long userId, String name, long key) {
        rollbackClientKeyInLane(clientLane(userId), userId, name, key);
    }

    // 账户 Lane 读取已准备持仓键时，Owner 仍可准备后续命令的身份。
    /** 用户与原持仓名到确定性键的并发正向表，支持异步发布读取。 */
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    /** 确定性持仓键到身份及发布保留计数的并发反向表。 */
    private final Map<PositionMapKey, PositionEntry> positions = new ConcurrentHashMap<>();

    /** 并发反向持仓表中保存的不可变长整数键对象。构造约定：保存确定性持仓长整数键，作为反向表的不可变键对象。参数 value：要长期保存的确定性持仓长整数键。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class PositionMapKey {
        /** 保存后不再变化的确定性持仓键。 */
        final long value;
        /** 用长整数键计算哈希，与查询探针保持一致。 */
        @Override public int hashCode() { return Long.hashCode(value); }
        /** 比较另一个存储键或可变查询探针中的长整数值。 */
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey key && value == key.value
                    || other instanceof PositionMapLookup lookup && value == lookup.value;
        }
    }

    /** 单个查找暂存复用的长整数探针，查询时避免新建存储键对象。 */
    private static final class PositionMapLookup {
        /** 本次要查询的确定性持仓键。 */
        long value;
        /** 用当前待查询的长整数计算与存储键一致的哈希。 */
        @Override public int hashCode() { return Long.hashCode(value); }
        /** 只与反向表存储键比较长整数值。 */
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey key && value == key.value;
        }
    }

    private PositionEntry positionEntry(long key) {
        return positionEntry(lookupScratch(), key);
    }

    private PositionEntry positionEntry(AccountPartition lane, long key) {
        return positionEntry(lane.identityLookupScratch(), key);
    }

    /** 先复用已登记且未退休的缓存项，再查并发表。 */
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

    /** 持仓身份及其发布保留计数。Owner 分配记录与正向登记是两个独立状态。 */
    private static final class PositionEntry extends java.util.concurrent.atomic.AtomicInteger {
        /** 该持仓项的不可变用户和原持仓名。 */
        final PositionIdentity identity;
        String verifiedInstrumentId;
        CorePositionSide verifiedSide;
        /** 仅 Owner 写的分配已记账标志，恢复项一开始就为真。 */
        boolean allocationRecorded;
        volatile boolean identityRegistered;
        /** 建立引用数为零的持仓项。恢复身份将分配已记账、正向已登记两标志一并设为真。 */
        PositionEntry(PositionIdentity identity, boolean recorded) {
            this.identity = identity;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        /** 建立持仓项并缓存已核对的标的和方向。 */
        PositionEntry(PositionIdentity identity, String instrumentId, CorePositionSide side, boolean recorded) {
            this.identity = identity;
            this.verifiedInstrumentId = instrumentId;
            this.verifiedSide = side;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        /** 先核对用户，再用已验证的标的、方向缓存或原持仓名核对。命中后保存验证结果，避免每次成交重建对冲持仓名。 */
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
    /** Owner 的分配序号到实际确定性持仓键，用于检查点后缀回滚。 */
    private final LongLongHashMap positionAllocationKeys = new LongLongHashMap();
    /** Owner 的确定性持仓键到分配序号，便于释放时撤掉分配记录。 */
    private final LongLongHashMap positionKeyAllocations = new LongLongHashMap();
    private int nextAssetId;
    private int nextSymbolId;
    /** 下次 Owner 持仓分配顺序号，从一起，不是哈希业务键。 */
    private long nextPositionKey = 1;
    /** 当前身份字典 Owner 线程，冷构建首次使用时绑定。 */
    private Thread owner;

    /** 首次调用绑定当前线程为 Owner，之后拒绝其他线程。冷构建与 Owner 交接沿用同一门禁。 */
    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("runtime identities are bound to another thread");
    }

    /** 清空身份表 Owner 绑定，供外层完成停用与交接后重新绑定。本方法不等待旧线程退出。 */
    public void releaseOwnerForHandoff() {
        owner = null;
    }

    /** Owner 先查原资产文本，未命中再规范化并分配从零开始的内部编号，发布反向数组。后续异常不自动回滚此前分配。 */
    public int assetId(String asset) {
        assertOwner();
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        String normalized = AssetBalance.normalizeAsset(asset);
        Integer existing = assetIds.get(normalized);
        if (existing != null) return existing;
        int id = nextAssetId++;
        assetIds.put(normalized, id);
        assets = storeIdentity(assets, id, normalized);
        return id;
    }

    /** Owner 查询现有资产编号，原文本未命中时才规范化。 */
    public Integer findAssetId(String asset) {
        assertOwner();
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        return assetIds.get(AssetBalance.normalizeAsset(asset));
    }

    /** 从已发布数组按内部资产编号读取文本。越界或空槽拒绝，不访问 Owner 正向字典。 */
    public String asset(int assetId) {
        String[] current = assets;
        String asset = assetId < 0 || assetId >= current.length ? null : current[assetId];
        if (asset == null) throw new IllegalArgumentException("unknown runtime asset id: " + assetId);
        return asset;
    }

    /** 解析永久数字标的文本，再取得或分配内部稠密编号。 */
    public int symbolId(String instrumentId) {
        return symbolId(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    /** 要求永久标的 ID 为正，Owner 查重后分配从零开始的内部编号，发布规范数字文本。 */
    public int symbolId(int instrumentId) {
        assertOwner();
        if (instrumentId <= 0) throw new IllegalArgumentException("positive instrumentId is required");
        int existing = symbolIds.getIfAbsent(instrumentId, -1);
        if (existing >= 0) return existing;
        int id = nextSymbolId++;
        symbolIds.put(instrumentId, id);
        symbols = storeIdentity(symbols, id, Integer.toString(instrumentId));
        return id;
    }

    /** 解析标的文本并查询现有内部编号。 */
    public Integer findSymbolId(String instrumentId) {
        int id = findSymbolIdValue(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
        return id < 0 ? null : id;
    }

    /** Owner 按永久整数 ID 查询内部编号，未知返回负一。 */
    public int findSymbolIdValue(int instrumentId) {
        assertOwner();
        return symbolIds.getIfAbsent(instrumentId, -1);
    }

    /** 只读反向数组，拒绝越界或尚未准备的标的槽，不进行身份分配。 */
    public String instrumentId(int symbolId) {
        String[] current = symbols;
        String instrumentId = symbolId < 0 || symbolId >= current.length ? null : current[symbolId];
        if (instrumentId == null) throw new IllegalArgumentException("unknown runtime instrumentId id: " + symbolId);
        return instrumentId;
    }

    /** Owner 冷投影客户端身份。 */
    public long clientKey(long userId, String clientOrderId) {
        assertOwner();
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        clientTable(userId).prepare(key, userId, clientOrderId, false);
        return key;
    }

    /** 核对所属 Lane 后保留一次客户端身份。 */
    public long prepareClientKeyInLane(AccountPartition lane, long userId, String clientOrderId) {
        ClientOrderIdRegistry table = clientTable(lane, userId);
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        long prepared = table.prepare(key, userId, clientOrderId, true);
        return prepared;
    }

    /** 空白或空单号返回零，其余按原始文本生成确定性正键，不修剪单号。 */
    private static long clientKeyOrZero(long userId, String name) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return name == null || name.isBlank() ? 0 : deterministicKey(userId, name);
    }

    /** 核对 Lane 后撤销本次准备引用，零键直接跳过。 */
    public void rollbackClientKeyInLane(AccountPartition lane, long userId, String clientOrderId, long preparedKey) {
        ClientOrderIdRegistry table = clientTable(lane, userId);
        if (preparedKey != 0) table.rollback(clientKeyValue(preparedKey), userId, clientOrderId);
    }

    /** 去掉准备结果的新分配符号，取得实际非负键。最小 long 取反溢出时报错。 */
    public static long clientKeyValue(long preparedKey) {
        return preparedKey < 0 ? Math.negateExact(preparedKey) : preparedKey;
    }

    /** 核对 Lane、标的和方向，用确定性键及反向身份匹配查已准备的持仓。 */
    public long findPositionKeyValueInLane(AccountPartition lane, long userId,
                                    CoreInstrument instrument, CorePositionSide side) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || instrument == null || side == null) {
            throw new IllegalStateException("position identity crossed account Lane");
        }
        long key = positionIdentityKey(userId, instrument.instrumentId(), side);
        PositionEntry entry = positionEntry(lane, key);
        return entry != null && entry.matches(userId, instrument.instrumentId(), side) ? key : 0;
    }

    /** 读取用户现有身份表中的客户端键，空白或未命中为零。 */
    public long findClientKeyValue(long userId, String clientOrderId) {
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        ClientOrderIdRegistry table = clientTable(userId);
        return table.find(key, userId, clientOrderId);
    }

    /** 通过显式 Lane 门禁读取用户客户端单号对应的现有键，不分配。 */
    public long findClientKeyValueInLane(AccountPartition lane, long userId, String clientOrderId) {
        ClientOrderIdRegistry table = clientTable(lane, userId);
        long key = clientKeyOrZero(userId, clientOrderId);
        return key == 0 ? 0 : table.find(key, userId, clientOrderId);
    }

    /** 在所属 Lane 按用户和实际客户端键反查原单号。 */
    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        ClientOrderIdRegistry table = clientTable(userId);
        return table.name(clientKey, userId);
    }

    /** 在显式所属 Lane 中释放客户端身份引用，不等待 Owner 代做。 */
    public void releaseClientKeyInLane(AccountPartition lane, long userId, long clientKey) {
        clientTable(lane, userId).release(clientKey, userId);
    }

    /** Owner 按用户和原持仓名复用或建立确定性键。 */
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

    /** 从合约取标的文本，按净仓或对冲方向准备 Owner 持仓身份。 */
    public long positionKey(long userId, CoreInstrument instrument, CorePositionSide side) {
        if (instrument == null) {
            throw new IllegalArgumentException("invalid position identity");
        }
        return positionKey(userId, instrument.instrumentId(), side);
    }

    /** Owner 先用标的和方向直接探测确定性键，命中不拼接持仓名。 */
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

    /** 尚未登记时把用户持仓名写入正向表并发布登记标志。外层负责确定性键碰撞检查。 */
    private void ensurePositionIdentityRegistered(PositionEntry entry, long key) {
        if (entry.identityRegistered) return;
        positionKeys.putIfAbsent(entry.identity, key);
        entry.identityRegistered = true;
    }

    /** 使用调用方已计算的持仓键，核对 Lane 与身份后保留一次发布引用并缓存。不重新计算传入键，退休项重试、计数溢出报错。 */
    public long retainPositionInLane(AccountPartition lane, long key, long userId, String instrumentId,
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

    /** Owner 消费一份 Lane 发布结果，先保证分配已记账再减少引用。 */
    public void releasePublishedPosition(long key) {
        assertOwner();
        PositionEntry entry = positionEntry(key);
        if (entry == null || entry.get() <= 0)
            throw new IllegalStateException("position publication has no retained identity");
        recordPositionAllocation(key, entry);
        if (entry.decrementAndGet() < 0) throw new IllegalStateException("position identity released twice");
    }

    /** Owner 为尚未记账的持仓项登记分配序号与确定性键，递增游标和字典版本，再标记已记账。 */
    private void recordPositionAllocation(long key, PositionEntry entry) {
        if (entry.allocationRecorded) return;
        trackAllocation(positionAllocationKeys, positionKeyAllocations, nextPositionKey++, key);
        entry.allocationRecorded = true;
    }

    /** 要求合约和方向存在，从合约取标的后只读查询已有持仓键。 */
    public long findPositionKeyValue(long userId, CoreInstrument instrument, CorePositionSide side) {
        assertOwner();
        if (userId <= 0 || instrument == null || side == null) return 0;
        return findPositionKeyValue(userId, instrument.instrumentId(), side);
    }

    /** 计算确定性键并核对反向身份。 */
    private long findPositionKeyValue(long userId, String instrumentId, CorePositionSide side) {
        long key = positionIdentityKey(userId, instrumentId, side);
        PositionEntry entry = positionEntry(key);
        return entry != null && entry.matches(userId, instrumentId, side) ? key : 0;
    }

    /** Owner 返回下次分配的顺序号，供本次准备失败时回滚。它不是确定性持仓业务键。 */
    public long positionCheckpoint() {
        assertOwner();
        return nextPositionKey;
    }

    /** 从分配尾部撤销检查点之后的身份，跳过已释放项。回滚不降低字典版本，失败前可能已有部分撤销。 */
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

    /** 以用户和原持仓名生成确定性正键。 */
    private static long deterministicPositionKey(PositionIdentity identity) {
        return deterministicKey(identity.userId(), identity.positionKey());
    }

    /** 净仓直接哈希标的，对冲仓按标的、冒号和方向的 UTF-8 字节流哈希。 */
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

    /** 只查已登记的原持仓名身份，缺失时报错，不在结算读路径临时分配。 */
    public long preparedPositionKey(long userId, String positionKey) {
        Long key = findPositionIdentity(userId, positionKey);
        if (key == null) throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    /** 核对订单所属 Lane，用订单预存的持仓身份键及标的、方向查已准备身份，不重建持仓名。 */
    public long preparedPositionKey(AccountPartition lane, Order order) {
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

    private static boolean positionNameEquals(String actual, String instrumentId, CorePositionSide side) {
        if (side == CorePositionSide.NET) return actual.equals(instrumentId);
        String sideName = side.name();
        int prefixLength = instrumentId.length();
        if (actual.length() != prefixLength + 1 + sideName.length()
                || actual.charAt(prefixLength) != ':') return false;
        if (!actual.regionMatches(0, instrumentId, 0, prefixLength)) return false;
        return actual.regionMatches(prefixLength + 1, sideName, 0, sideName.length());
    }

    /** 按实际持仓键反查原持仓名，同时核对该身份属于指定用户。 */
    public String positionKey(long userId, long positionKey) {
        PositionEntry entry = positionEntry(positionKey);
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    /** 按确定性持仓键反查用户和原持仓名。 */
    public PositionIdentity positionIdentity(long positionKey) {
        PositionEntry entry = positionEntry(positionKey);
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null) {
            throw new IllegalArgumentException("unknown runtime position key: " + positionKey);
        }
        return identity;
    }

    /** Owner 汇总当前绑定 Lane 或独立冷表中的存活客户端身份数，统计身份条目而不是保留引用总数。 */
    public int clientIdentityCount() {
        assertOwner();
        int count = 0;
        if (clientRuntime == null) return 0;
        for (int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            AccountPartition lane = clientRuntime.accountLane(laneIndex);
            count = Math.addExact(count, lane.clientIdentities().publishedCount());
        }
        return count;
    }

    /** Owner 仅在引用数为零时把持仓项标记退休，再移除正向、反向和分配记录。 */
    public void releasePositionKey(long positionKey) {
        assertOwner();
        PositionEntry entry = positionEntry(positionKey);
        if (entry == null || !entry.compareAndSet(0, -1)) return;
        // 先移除旧正向身份，再让新一代身份有机会取得同一个确定性键。
        positionKeys.remove(entry.identity, positionKey);
        entry.identityRegistered = false;
        ownerLookupScratch.positionEntries.remove(positionKey);
        positions.remove(new PositionMapKey(positionKey), entry);
        removeAllocation(positionAllocationKeys, positionKeyAllocations, positionKey);
    }

    /** 按用户低位字节在前的八字节和原文本 UTF-8 流计算确定性哈希，最后保留正值且把零改成一。 */
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

    /** long 乘法溢出是原哈希算法的环绕行为。 */
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

    /** 同时登记分配序号到确定性键及反向关系。 */
    private static void trackAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                        long allocation, long key) {
        if (allocations.containsKey(allocation) || allocationsByKey.containsKey(key)) {
            throw new IllegalStateException("runtime identity allocation collision");
        }
        allocations.put(allocation, key);
        allocationsByKey.put(key, allocation);
    }

    /** 没有记录直接返回，不一致时报错且不自动恢复此前删除。 */
    private static void removeAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                         long key) {
        long allocation = allocationsByKey.removeKeyIfAbsent(key, 0);
        if (allocation == 0) return;
        if (allocations.removeKeyIfAbsent(allocation, key) != key) {
            throw new IllegalStateException("runtime identity allocation index is inconsistent");
        }
    }

    /** 用户范围内的原持仓名值。 */
    public record PositionIdentity(long userId, String positionKey) implements Comparable<PositionIdentity> {
        /** 先按用户数字，再按原持仓名字典序排序。 */
        @Override
        public int compareTo(PositionIdentity other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : positionKey.compareTo(other.positionKey);
        }
    }
}
