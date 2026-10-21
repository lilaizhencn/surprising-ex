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

/**
 * 运行期身份表：把资产和永久数字标的映射成内部稠密编号，并保存用户范围的客户端单号、持仓名与确定性长整数键。资产、标的和分配记录由 Owner 写；客户端身份归所属账户 Lane，持仓身份在发布结果消费完之前由引用计数保留。这里只管理身份，不保存余额、订单或持仓正文。
 */
public final class RuntimeIdentityRegistry {
    /** Owner 独占的持仓查找探针和缓存，不与工作 Lane 共用。 */
    private final LookupScratch ownerLookupScratch = new LookupScratch();

    /**
     * 按调用者选择查找探针：Owner 和当前账户命令作用域各复用自己的实例，其他读取者每次新建局部探针，避免并发改同一探针。
     */
    private LookupScratch lookupScratch() {
        if (Thread.currentThread() == owner) return ownerLookupScratch;
        // 本线程当前所处的账户命令作用域，没有则使用新建的局部探针
        AccountLaneState lane = clientRuntime == null ? null : clientRuntime.currentLaneCommandScope();
        return lane == null ? new LookupScratch() : lane.identityLookupScratch();
    }

    /**
     * 单个 Owner 或账户 Lane 独占的查找暂存：复用正向、反向探针和已登记持仓项缓存；普通读取者使用独立局部实例。
     */
    public static final class LookupScratch {
        /** 按用户与原持仓名查询正向表的可变探针。 */
        private final PositionLookup position = new PositionLookup();
        /** 按确定性长整数键查询反向表的可变探针。 */
        private final PositionMapLookup positionMap = new PositionMapLookup();
        /** 本暂存独占的已登记且未退休持仓项缓存，使用时重新核对状态。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionEntry> positionEntries =
                new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    }

    /**
     * 借用当前线程的持仓名探针查正向表，结束时清掉临时文本引用；只读，不分配业务身份。
     * @param userId 该身份所属用户
     * @param key 要查询的原持仓名文本
     */
    private Long findPositionIdentity(long userId, String key) {
        // 只供本调用者使用的正向或反向查询探针
        PositionLookup lookup = lookupScratch().position;
        lookup.userId = userId;
        lookup.key = key;
        try { return positionKeys.get(lookup); }
        finally { lookup.key = null; }
    }

    /**
     * 只在一次查询中填入用户、持仓名的可变探针，不作为长期字典键；查询结束清空名称引用。
     */
    private static final class PositionLookup {
        /** 本次正向查询所属用户。 */
        long userId;
        /** 本次正向查询的原持仓名，结束后清空引用。 */
        String key;
        /**
         * 按用户及持仓名计算与 PositionIdentity 相同的哈希，供可变探针查询不可变身份键。
         */
        @Override public int hashCode() { return 31 * Long.hashCode(userId) + key.hashCode(); }
        /**
         * 只和存储用的 PositionIdentity 比较用户、持仓名；探针不作为表中长期保存的键。
         * @param other 待与探针比较的存储用 PositionIdentity 对象
         */
        @Override public boolean equals(Object other) {
            return other instanceof PositionIdentity /* 当前比较、导入或哈希的用户范围身份 */ identity
                    && userId == identity.userId() && key.equals(identity.positionKey());
        }
    }

    // 资产、标的正向字典及分配记录只由 Owner 修改；只增身份的反向数组通过 volatile 发布。
    // 可释放的身份另按账户所有权及并发表规则处理，供异步事实发布读取。
    /** Owner 独占的规范资产文本到内部编号字典。 */
    private final Map<String, Integer> assetIds = new HashMap<>();
    /** 通过 volatile 引用发布的资产反向数组，容量足够时写入后重新发布同一数组。 */
    private volatile String[] assets = new String[16];
    /** Owner 独占的永久正整数标的 ID 到内部稠密编号的映射。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap symbolIds = new org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap();
    /** 按内部标的编号存规范数字文本的已发布数组，不以永久 ID 作为下标。 */
    private volatile String[] symbols = new String[16];
    /** 核对异步客户端身份操作是否在用户所属 Lane 的固定路由规则。 */
    private LaneTopology clientTopology = LaneTopology.configured(false);
    /** 客户端身份权威表所在运行时，尚未绑定时为空。 */
    private TradingRuntimeState clientRuntime;

    /** 仅独立冷构建按用户保存的身份表，成功迁入运行时后清空并释放。 */
    private org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<LaneClientIdentities> unboundClients;

    /**
     * 仅在新旧账户工作线程均未启动时迁移客户端身份：先导入新运行时，成功后清旧表并切换权威引用。导入失败保留旧来源，不发布不完整的新运行时。
     * @param runtime 要接入客户端身份的冷运行时，账户线程尚未启动
     */
    public void bindClientLanes(TradingRuntimeState runtime) {
        assertOwner();
        if (clientRuntime == runtime) return;
        if (runtime.accountLanesStarted() || clientRuntime != null && clientRuntime.accountLanesStarted())
            throw new IllegalStateException("client identities already belong to a runtime");
        // 先把身份导入新的冷运行时，成功后才释放旧来源；导入失败保留原权威表，
        // 不把未完整导入的新运行时设为当前运行时。
        if (clientRuntime != null) {
            for (/* 新旧冷运行时账户 Lane 的遍历下标 */ int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
                // 当前正导入新冷运行时的旧账户 Lane
                AccountLaneState lane = clientRuntime.accountLane(laneIndex);
                lane.clientIdentities().copyTo(runtime);
            }
        } else if (unboundClients != null) unboundClients.forEachValue(/* 当前用户或迁移中的客户端身份表 */ table -> table.copyTo(runtime));
        if (clientRuntime != null) {
            for (/* 新旧冷运行时账户 Lane 的遍历下标 */ int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
                // 新运行时已导入完成，当前可清空的旧账户 Lane
                AccountLaneState lane = clientRuntime.accountLane(laneIndex);
                lane.clientIdentities().clear();
            }
        }
        if (unboundClients != null) { unboundClients.clear(); unboundClients = null; }
        clientTopology = runtime.topology();
        clientRuntime = runtime;
    }

    /**
     * 按已绑定运行时和用户定位账户 Lane；尚未绑定时拒绝。取得引用本身不授予跨线程访问权限。
     * @param userId 该身份所属用户
     */
    private AccountLaneState clientLane(long userId) {
        if (clientRuntime == null) throw new IllegalStateException("client identity requires a runtime");
        return clientRuntime.accountLaneForUserReadOnly(userId);
    }

    /**
     * 取用户的客户端身份表；绑定后使用账户 Lane 的表，独立冷构建时由 Owner 按用户延迟建表。
     * @param userId 该身份所属用户
     */
    private LaneClientIdentities clientTable(long userId) {
        if (clientRuntime != null) return clientLane(userId).clientIdentities();
        assertOwner();
        if (unboundClients == null)
            unboundClients = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = unboundClients.get(userId);
        if (table == null) { table = new LaneClientIdentities(null); unboundClients.put(userId, table); }
        return table;
    }

    /**
     * 读取现有用户身份表，不创建冷表；绑定后仍须由调用方遵守账户访问边界。
     * @param userId 该身份所属用户
     */
    private LaneClientIdentities clientTableReadOnly(long userId) {
        if (clientRuntime != null) return clientLane(userId).clientIdentities();
        // 独立冷构建的客户端表引用，不存在则查询未命中
        var clients = unboundClients;
        return clients == null ? null : clients.get(userId);
    }

    /**
     * 先核对 Lane 所有权、用户路由及运行时实例，再返回其身份表；冷构建仍走按用户保存的临时表。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     */
    private LaneClientIdentities clientTable(AccountLaneState lane, long userId) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || (clientRuntime != null && clientRuntime.accountLane(lane.laneId()) != lane))
            throw new IllegalStateException("client identity crossed account Lane");
        if (clientRuntime != null) return lane.clientIdentities();
        return clientTable(userId);
    }

    /**
     * 定位用户所属 Lane，按 Lane 准入规则准备并保留客户端身份；返回值符号标识此次是否新分配。
     * @param userId 该身份所属用户
     * @param name 本次准入要保留的原客户端单号
     */
    public long prepareClientKeyInCurrentLane(long userId, String name) {
        return prepareClientKeyInLane(clientLane(userId), userId, name);
    }

    /**
     * 定位用户所属 Lane，撤销该次准备所持的客户端身份引用。
     * @param userId 该身份所属用户
     * @param name 本次准备的原客户端单号
     * @param key 本次准备返回的带符号客户端键
     */
    public void rollbackClientKeyInCurrentLane(long userId, String name, long key) {
        rollbackClientKeyInLane(clientLane(userId), userId, name, key);
    }

    /**
     * Owner 从 Lane 回执累计新身份分配数，精确加到字典版本；加法溢出报错，本方法不单独检查计数正负。
     * @param count 从 Lane 回执汇总的新客户端身份分配次数
     */
    public void recordLaneClientAllocations(long count) {
        assertOwner();
        dictionaryVersion = Math.addExact(dictionaryVersion, count);
    }

    // 账户 Lane 读取已准备持仓键时，Owner 仍可准备后续命令的身份。
    /** 用户与原持仓名到确定性键的并发正向表，支持异步发布读取。 */
    private final Map<PositionIdentity, Long> positionKeys = new ConcurrentHashMap<>();
    /** 确定性持仓键到身份及发布保留计数的并发反向表。 */
    private final Map<PositionMapKey, PositionEntry> positions = new ConcurrentHashMap<>();

    /**
     * 并发反向持仓表中保存的不可变长整数键对象。
     */
    private static final class PositionMapKey {
        /** 保存后不再变化的确定性持仓键。 */
        final long value;
        /**
         * 保存确定性持仓长整数键，作为反向表的不可变键对象。
         * @param value 要长期保存的确定性持仓长整数键
         */
        PositionMapKey(long value) { this.value = value; }
        /**
         * 用长整数键计算哈希，与查询探针保持一致。
         */
        @Override public int hashCode() { return Long.hashCode(value); }
        /**
         * 比较另一个存储键或可变查询探针中的长整数值。
         * @param other 另一个不可变存储键或可变长整数查询探针
         */
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey /* 待比较的不可变持仓存储键 */ key && value == key.value
                    || other instanceof PositionMapLookup /* 只供本调用者使用的正向或反向查询探针 */ lookup && value == lookup.value;
        }
    }

    /**
     * 单个查找暂存复用的长整数探针，查询时避免新建存储键对象。
     */
    private static final class PositionMapLookup {
        /** 本次要查询的确定性持仓键。 */
        long value;
        /**
         * 用当前待查询的长整数计算与存储键一致的哈希。
         */
        @Override public int hashCode() { return Long.hashCode(value); }
        /**
         * 只与反向表存储键比较长整数值；不把可变探针插入表。
         * @param other 待与探针比较的不可变长整数存储键
         */
        @Override public boolean equals(Object other) {
            return other instanceof PositionMapKey /* 待比较的不可变持仓存储键 */ key && value == key.value;
        }
    }

    /**
     * 使用当前调用者的私有探针和缓存查反向持仓表。
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private PositionEntry positionEntry(long key) {
        return positionEntry(lookupScratch(), key);
    }

    /**
     * 使用指定 Lane 独占的探针和缓存查反向持仓表，访问权限由外层入口核对。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private PositionEntry positionEntry(AccountLaneState lane, long key) {
        return positionEntry(lane.identityLookupScratch(), key);
    }

    /**
     * 先复用已登记且未退休的缓存项，再查并发表；只缓存有效项并清理失效缓存。返回的表项仍可能已退休或尚未登记，调用方继续核对或重试。
     * @param scratch 本调用者独占的探针和持仓项缓存
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private PositionEntry positionEntry(LookupScratch scratch, long key) {
        // 本探针缓存中曾查到的持仓项，复用前核对退休与登记状态
        PositionEntry cached = scratch.positionEntries.get(key);
        if (cached != null && cached.get() >= 0 && cached.identityRegistered) {
            return cached;
        }
        // 只供本调用者使用的正向或反向查询探针
        PositionMapLookup lookup = scratch.positionMap;
        lookup.value = key;
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positions.get(lookup);
        if (entry != null && entry.get() >= 0 && entry.identityRegistered) {
            scratch.positionEntries.put(key, entry);
        } else if (cached != null) {
            scratch.positionEntries.remove(key);
        }
        return entry;
    }

    /**
     * 持仓身份及其发布保留计数。继承的原子整数大于零表示仍有 Lane 发布待消费，零表示可释放，负一表示已经退休；Owner 分配记录与正向登记是两个独立状态。
     */
    private static final class PositionEntry extends java.util.concurrent.atomic.AtomicInteger {
        /** 该持仓项的不可变用户和原持仓名。 */
        final PositionIdentity identity;
        /** 上次匹配成功的标的文本，供成交读取直接核对。 */
        String verifiedInstrumentId;
        /** 与已验证标的配套的持仓方向。 */
        CorePositionSide verifiedSide;
        /** 仅 Owner 写的分配已记账标志，恢复项一开始就为真。 */
        boolean allocationRecorded; // 仅 Owner 修改，快照恢复的身份已完成分配记账。
        /** 正向身份已登记的发布标志，退休删除时清为假。 */
        volatile boolean identityRegistered;
        /**
         * 建立引用数为零的持仓项；恢复身份将分配已记账、正向已登记两标志一并设为真。
         * @param identity 包含用户与原持仓名的不可变身份
         * @param recorded 是否把该项视为恢复后的已记账且已登记身份
         */
        PositionEntry(PositionIdentity identity, boolean recorded) {
            this.identity = identity;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        /**
         * 建立持仓项并缓存已核对的标的和方向；记录标志决定是否把它视为已登记、已记账的身份。
         * @param identity 包含用户与原持仓名的不可变身份
         * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
         * @param side 净仓、多头或空头持仓方向
         * @param recorded 是否把该项视为恢复后的已记账且已登记身份
         */
        PositionEntry(PositionIdentity identity, String instrumentId, CorePositionSide side, boolean recorded) {
            this.identity = identity;
            this.verifiedInstrumentId = instrumentId;
            this.verifiedSide = side;
            this.allocationRecorded = recorded;
            this.identityRegistered = recorded;
        }

        /**
         * 先核对用户，再用已验证的标的、方向缓存或原持仓名核对；命中后保存验证结果，避免每次成交重建对冲持仓名。
         * @param userId 该身份所属用户
         * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
         * @param side 净仓、多头或空头持仓方向
         */
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
    /** 下次资产内部编号，从零起。 */
    private int nextAssetId;
    /** 下次标的内部稠密编号，从零起。 */
    private int nextSymbolId;
    /** 下次 Owner 持仓分配顺序号，从一起，不是哈希业务键。 */
    private long nextPositionKey = 1;
    /** Owner 已记录的资产、标的及客户端、持仓分配版本，释放不倒退。 */
    private long dictionaryVersion;
    /** 当前身份字典 Owner 线程，冷构建首次使用时绑定。 */
    private Thread owner;

    /**
     * 首次调用绑定当前线程为 Owner，之后拒绝其他线程；冷构建与 Owner 交接沿用同一门禁。
     */
    public void assertOwner() {
        // 调用当前身份字典的实际线程
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("runtime identities are bound to another thread");
    }

    /**
     * 清空身份表 Owner 绑定，供外层完成停用与交接后重新绑定；本方法不等待旧线程退出。
     */
    public void releaseOwnerForHandoff() {
        owner = null;
    }

    /**
     * Owner 先查原资产文本，未命中再规范化并分配从零开始的内部编号，发布反向数组并增加版本；后续异常不自动回滚此前分配。
     * @param asset 要规范化或查询的资产文本
     */
    public int assetId(String asset) {
        assertOwner();
        // 原文本命中的是已验证身份，只在未命中时规范化名称。
        // 正向字典已有的身份编号或键，存在则不分配
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        // 原文本未命中后按资产规则规范化的名称
        String normalized = AssetBalance.normalizeAsset(asset);
        // 规范资产名已经对应的内部编号，存在时复用
        Integer existing = assetIds.get(normalized);
        if (existing != null) return existing;
        // 本次新分配的内部资产编号
        int id = nextAssetId++;
        assetIds.put(normalized, id);
        assets = storeIdentity(assets, id, normalized);
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return id;
    }

    /**
     * Owner 查询现有资产编号，原文本未命中时才规范化；未知资产返回空，不分配。
     * @param asset 要规范化或查询的资产文本
     */
    public Integer findAssetId(String asset) {
        assertOwner();
        // 正向字典已有的身份编号或键，存在则不分配
        Integer known = assetIds.get(asset);
        if (known != null) return known;
        return assetIds.get(AssetBalance.normalizeAsset(asset));
    }

    /**
     * 从已发布数组按内部资产编号读取文本；越界或空槽拒绝，不访问 Owner 正向字典。
     * @param assetId 内部资产数组编号
     */
    public String asset(int assetId) {
        // 本次读取一次 volatile 资产数组引用，范围检查与取值使用同一数组
        String[] current = assets;
        // 按内部资产编号取得的文本
        String asset = assetId < 0 || assetId >= current.length ? null : current[assetId];
        if (asset == null) throw new IllegalArgumentException("unknown runtime asset id: " + assetId);
        return asset;
    }

    /**
     * 解析永久数字标的文本，再取得或分配内部稠密编号；外部数字身份与内部编号不是同一个值。
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     */
    public int symbolId(String instrumentId) {
        return symbolId(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    /**
     * 要求永久标的 ID 为正，Owner 查重后分配从零开始的内部编号，发布规范数字文本并增加版本。
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     */
    public int symbolId(int instrumentId) {
        assertOwner();
        if (instrumentId <= 0) throw new IllegalArgumentException("positive instrumentId is required");
        // 永久标的已有的内部稠密编号，负一表示未分配
        int existing = symbolIds.getIfAbsent(instrumentId, -1);
        if (existing >= 0) return existing;
        // 本次分配的新内部稠密标的编号，零也是有效编号
        int id = nextSymbolId++;
        symbolIds.put(instrumentId, id);
        symbols = storeIdentity(symbols, id, Integer.toString(instrumentId));
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return id;
    }

    /**
     * 解析标的文本并查询现有内部编号；未知返回空，内部编号零仍是有效结果。
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     */
    public Integer findSymbolId(String instrumentId) {
        // 已分配的内部稠密编号，负一随后转换为空值
        int id = findSymbolIdValue(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
        return id < 0 ? null : id;
    }

    /**
     * Owner 按永久整数 ID 查询内部编号，未知返回负一；零有效，查询本身不分配且不额外检查参数是否为正。
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     */
    public int findSymbolIdValue(int instrumentId) {
        assertOwner();
        return symbolIds.getIfAbsent(instrumentId, -1);
    }

    /**
     * 按内部稠密标的编号取得已发布的永久数字文本。
     * @param symbolId 内部稠密标的编号，不是外部永久 ID
     */
    public String instrumentId(int symbolId) {
        return preparedSymbol(symbolId);
    }

    /**
     * 只读反向数组，拒绝越界或尚未准备的标的槽，不进行身份分配。
     * @param symbolId 内部稠密标的编号，不是外部永久 ID
     */
    public String preparedSymbol(int symbolId) {
        // 本次读取一次 volatile 标的数组引用，范围检查与取值使用同一数组
        String[] current = symbols;
        // 该内部稠密编号在已发布数组中对应的永久数字标的文本
        String instrumentId = symbolId < 0 || symbolId >= current.length ? null : current[symbolId];
        if (instrumentId == null) throw new IllegalArgumentException("unknown runtime instrumentId id: " + symbolId);
        return instrumentId;
    }

    /**
     * Owner 冷投影客户端身份；空白单号返回零，重复身份不额外保留。新身份增加字典版本，对外返回实际正键。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public long clientKey(long userId, String clientOrderId) {
        assertOwner();
        // 当前用户身份的确定性长整数键
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        // 客户端表返回的带符号准备结果，负号表示此次新分配
        long prepared = clientTable(userId).prepare(key, userId, clientOrderId, false);
        if (prepared < 0) dictionaryVersion = Math.incrementExact(dictionaryVersion);
        return key;
    }

    /**
     * Owner 为本次准入保留客户端身份引用，新分配计入版本；对外返回实际正键，不保留 Lane 准备结果的符号。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public long prepareClientKey(long userId, String clientOrderId) {
        assertOwner();
        // 当前用户身份的确定性长整数键
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        // 客户端表返回的带符号准备结果，负号表示此次新分配
        long prepared = clientTable(userId).prepare(key, userId, clientOrderId, true);
        if (prepared < 0) recordLaneClientAllocations(1);
        return key;
    }

    /**
     * 核对所属 Lane 后保留一次客户端身份；新分配增加 Lane 本地分配数并返回负键，已有身份返回正键，零表示无客户端单号。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public long prepareClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId) {
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = clientTable(lane, userId);
        // 当前用户身份的确定性长整数键
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        // 客户端表返回的带符号准备结果，负号表示此次新分配
        long prepared = table.prepare(key, userId, clientOrderId, true);
        if (prepared < 0) lane.clientIdentityAllocations(Math.incrementExact(lane.clientIdentityAllocations()));
        // 仅返回值用负号标记新分配，Lane 状态中存储的实际身份键始终为正。
        return prepared;
    }

    /**
     * 要求用户为正；空白或空单号返回零，其余按原始文本生成确定性正键，不修剪单号。
     * @param userId 该身份所属用户
     * @param name 用户原客户端单号，空白或空值表示未提供
     */
    private static long clientKeyOrZero(long userId, String name) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return name == null || name.isBlank() ? 0 : deterministicKey(userId, name);
    }

    /**
     * Owner 校验输入后解码准备结果，撤销这次客户端身份保留；其余仍存活的引用不受影响。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     * @param preparedKey 本次客户端身份准备结果，负号表示新建，绝对值为实际键
     */
    public void rollbackPreparedClientKey(long userId, String clientOrderId, long preparedKey) {
        assertOwner();
        if (userId <= 0 || clientOrderId == null || preparedKey == 0)
            throw new IllegalArgumentException("invalid prepared client key rollback");
        clientTable(userId).rollback(clientKeyValue(preparedKey), userId, clientOrderId);
    }

    /**
     * 核对 Lane 后撤销本次准备引用，零键直接跳过。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     * @param preparedKey 本次客户端身份准备结果，负号表示新建，绝对值为实际键
     */
    public void rollbackClientKeyInLane(AccountLaneState lane, long userId, String clientOrderId, long preparedKey) {
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = clientTable(lane, userId);
        if (preparedKey != 0) table.rollback(clientKeyValue(preparedKey), userId, clientOrderId);
    }

    /**
     * 去掉准备结果的新分配符号，取得实际非负键；最小 long 取反溢出时报错。
     * @param preparedKey 本次客户端身份准备结果，负号表示新建，绝对值为实际键
     */
    public static long clientKeyValue(long preparedKey) {
        return preparedKey < 0 ? Math.negateExact(preparedKey) : preparedKey;
    }

    /**
     * 用负号判断准备结果是否表示此次新建身份；零和正值均不是新建。
     * @param preparedKey 本次客户端身份准备结果，负号表示新建，绝对值为实际键
     */
    public static boolean newClientIdentity(long preparedKey) { return preparedKey < 0; }

    /**
     * 核对所属 Lane 后按用户和原持仓名查身份；无结果返回空，不分配。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param key 用户原持仓名文本
     */
    Long findPositionKeyInLane(AccountLaneState lane, long userId, String key) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)) throw new IllegalStateException("position identity crossed account Lane");
        // 只供本调用者使用的正向或反向查询探针
        PositionLookup lookup = lane.identityLookupScratch().position;
        lookup.userId = userId;
        lookup.key = key;
        try { return positionKeys.get(lookup); }
        finally { lookup.key = null; }
    }

    /**
     * 沿用 Lane 持仓名查询，把未命中的空值转为零。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param key 用户原持仓名文本
     */
    long findPositionKeyValueInLane(AccountLaneState lane, long userId, String key) {
        // 原持仓名查到的可空身份键，空值转换为零
        Long value = findPositionKeyInLane(lane, userId, key);
        return value == null ? 0 : value;
    }

    /**
     * 核对 Lane、标的和方向，用确定性键及反向身份匹配查已准备的持仓；未命中返回零。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param instrument 提供永久标的文本的合约配置
     * @param side 净仓、多头或空头持仓方向
     */
    public long findPositionKeyValueInLane(AccountLaneState lane, long userId,
                                    CoreInstrument instrument, CorePositionSide side) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId)
                || instrument == null || side == null) {
            throw new IllegalStateException("position identity crossed account Lane");
        }
        // 当前用户身份的确定性长整数键
        long key = positionIdentityKey(userId, instrumentId(instrument), side);
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(lane, key);
        return entry != null && entry.matches(userId, instrument.instrumentId(), side) ? key : 0;
    }

    /**
     * 要求合约对象存在，取其永久数字标的文本。
     * @param instrument 提供永久标的文本的合约配置
     */
    private static String instrumentId(CoreInstrument instrument) {
        return instrument.instrumentId();
    }

    /**
     * 查询现有客户端身份，把零结果转换成空值。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public Long findClientKey(long userId, String clientOrderId) {
        // 当前用户身份的确定性长整数键
        long key = findClientKeyValue(userId, clientOrderId);
        return key == 0 ? null : key;
    }

    /**
     * 读取用户现有身份表中的客户端键，空白或未命中为零；调用方须处于所属 Lane 或外部已建立的冷读边界。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public long findClientKeyValue(long userId, String clientOrderId) {
        // 当前用户身份的确定性长整数键
        long key = clientKeyOrZero(userId, clientOrderId);
        if (key == 0) return 0;
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = clientTableReadOnly(userId);
        return table == null ? 0 : table.find(key, userId, clientOrderId);
    }

    /**
     * 通过显式 Lane 门禁读取用户客户端单号对应的现有键，不分配。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public long findClientKeyValueInLane(AccountLaneState lane, long userId, String clientOrderId) {
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = clientTable(lane, userId);
        // 当前用户身份的确定性长整数键
        long key = clientKeyOrZero(userId, clientOrderId);
        return key == 0 ? 0 : table.find(key, userId, clientOrderId);
    }

    /**
     * 按用户和实际客户端键反查原单号；零键返回空文本，没有该用户身份表则返回空值。
     * @param userId 该身份所属用户
     * @param clientKey 用户范围的实际客户端身份键，零表示未提供客户端单号
     */
    public String clientOrderId(long userId, long clientKey) {
        if (clientKey == 0) return "";
        // 当前用户或迁移中的客户端身份表
        LaneClientIdentities table = clientTableReadOnly(userId);
        return table == null ? null : table.name(clientKey, userId);
    }

    /**
     * Owner 释放一份客户端身份引用，身份表负责只在最后一份引用释放后删除。
     * @param userId 该身份所属用户
     * @param clientKey 用户范围的实际客户端身份键，零表示未提供客户端单号
     */
    public void releaseClientKey(long userId, long clientKey) {
        assertOwner();
        clientTable(userId).release(clientKey, userId);
    }

    /**
     * 在显式所属 Lane 中释放客户端身份引用，不等待 Owner 代做。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param clientKey 用户范围的实际客户端身份键，零表示未提供客户端单号
     */
    public void releaseClientKeyInLane(AccountLaneState lane, long userId, long clientKey) {
        clientTable(lane, userId).release(clientKey, userId);
    }

    /**
     * Owner 按用户和原持仓名复用或建立确定性键；反向冲突时报错，成功后登记正向身份并只记一次分配。
     * @param userId 该身份所属用户
     * @param positionKey 用户原持仓名文本，必须非空白
     */
    public long positionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) {
            throw new IllegalArgumentException("invalid position identity");
        }
        // 正向表已有的确定性持仓键，命中即可返回
        Long existing = findPositionIdentity(userId, positionKey);
        if (existing != null) return existing;
        // 当前比较、导入或哈希的用户范围身份
        PositionIdentity identity = new PositionIdentity(userId, positionKey);
        // 当前用户身份的确定性长整数键
        long key = deterministicPositionKey(identity);
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = new PositionEntry(identity, false);
        // putIfAbsent 返回的已有项，必须核对实际用户和持仓名
        PositionEntry collision = positions.putIfAbsent(new PositionMapKey(key), entry);
        if (collision != null && !collision.identity.equals(identity)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        // putIfAbsent 后实际保留在反向表中的身份项，可能是本次新建也可能是已有项
        PositionEntry retained = collision == null ? entry : collision;
        ensurePositionIdentityRegistered(retained, key);
        recordPositionAllocation(key, retained);
        ownerLookupScratch.positionEntries.put(key, retained);
        return key;
    }

    /**
     * 从合约取标的文本，按净仓或对冲方向准备 Owner 持仓身份。
     * @param userId 该身份所属用户
     * @param instrument 提供永久标的文本的合约配置
     * @param side 净仓、多头或空头持仓方向
     */
    public long positionKey(long userId, CoreInstrument instrument, CorePositionSide side) {
        if (instrument == null) {
            throw new IllegalArgumentException("invalid position identity");
        }
        return positionKey(userId, instrument.instrumentId(), side);
    }

    /**
     * Owner 先用标的和方向直接探测确定性键，命中不拼接持仓名；未命中时创建原名、核对碰撞并登记身份及分配。
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public long positionKey(long userId, String instrumentId, CorePositionSide side) {
        assertOwner();
        if (userId <= 0 || instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("invalid position identity");
        }
        // 当前用户身份的确定性长整数键
        long key = positionIdentityKey(userId, instrumentId, side);
        // 按确定性键找到的已有持仓项，还须核对用户、标的和方向
        PositionEntry existing = positionEntry(key);
        if (existing != null) {
            if (!existing.matches(userId, instrumentId, side)) {
                throw new IllegalStateException("deterministic position identity collision");
            }
            return key;
        }
        // 新建身份时才构造的原持仓名或当前导入的原文本
        String name = side == CorePositionSide.NET ? instrumentId : instrumentId + ':' + side.name();
        // 为未命中身份建立、尚待 putIfAbsent 竞争发布的新持仓项
        PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), instrumentId, side, false);
        // putIfAbsent 返回的已有项，必须核对实际用户和持仓名
        PositionEntry collision = positions.putIfAbsent(new PositionMapKey(key), created);
        if (collision != null && !collision.matches(userId, instrumentId, side)) {
            throw new IllegalStateException("deterministic position identity collision");
        }
        // putIfAbsent 后实际保留在反向表中的身份项，后续为它登记正向身份和分配
        PositionEntry retained = collision == null ? created : collision;
        ensurePositionIdentityRegistered(retained, key);
        recordPositionAllocation(key, retained);
        ownerLookupScratch.positionEntries.put(key, retained);
        return key;
    }

    /**
     * 尚未登记时把用户持仓名写入正向表并发布登记标志；外层负责确定性键碰撞检查。
     * @param entry 当前要登记、记账或检查的持仓身份项
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private void ensurePositionIdentityRegistered(PositionEntry entry, long key) {
        if (entry.identityRegistered) return;
        positionKeys.putIfAbsent(entry.identity, key);
        entry.identityRegistered = true;
    }

    /**
     * 所属 Lane 为尚未消费的发布结果保留持仓名身份：建立或找到同名项，遇到退休项重试，用 CAS 增加引用并保证正向登记。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param name 该发布结果必须保留的完整原持仓名
     */
    public long retainPositionInLane(AccountLaneState lane, long userId, String name) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId() || name == null || name.isBlank())
            throw new IllegalArgumentException("position identity belongs to another Lane");
        // 当前用户身份的确定性长整数键
        long key = deterministicKey(userId, name);
        while (true) {
            // 本次反向表查到或发布的持仓身份项
            PositionEntry entry = positionEntry(lane, key);
            if (entry == null) {
                // 为未命中身份建立、尚待 putIfAbsent 竞争发布的新持仓项
                PositionEntry created = new PositionEntry(new PositionIdentity(userId, name), false);
                entry = positions.putIfAbsent(new PositionMapKey(key), created);
                if (entry == null) entry = created;
            }
            if (entry.identity.userId() != userId || !entry.identity.positionKey().equals(name))
                throw new IllegalStateException("deterministic position identity collision");
            // CAS 读取的当前发布保留计数，负数表示退休并需要重试
            int uses = entry.get();
            if (uses < 0) { Thread.onSpinWait(); continue; }
            if (!entry.compareAndSet(uses, Math.incrementExact(uses))) continue;
            ensurePositionIdentityRegistered(entry, key);
            return key;
        }
    }

    /**
     * 按标的和方向计算确定性键，再保留所属 Lane 发布所需的一份持仓身份引用。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public long retainPositionInLane(AccountLaneState lane, long userId, String instrumentId,
                              CorePositionSide side) {
        if (instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("position identity belongs to another Lane");
        }
        return retainPositionInLane(lane, positionIdentityKey(userId, instrumentId, side),
                userId, instrumentId, side);
    }

    /**
     * 使用调用方已计算的持仓键，核对 Lane 与身份后保留一次发布引用并缓存；不重新计算传入键，退休项重试、计数溢出报错。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param key 待查询、登记或释放的确定性身份长整数键
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public long retainPositionInLane(AccountLaneState lane, long key, long userId, String instrumentId,
                              CorePositionSide side) {
        lane.assertOwner();
        if (clientTopology.accountLaneId(userId) != lane.laneId()
                || instrumentId == null || instrumentId.isBlank() || side == null) {
            throw new IllegalArgumentException("position identity belongs to another Lane");
        }
        while (true) {
            // 本次反向表查到或发布的持仓身份项
            PositionEntry entry = positionEntry(lane, key);
            if (entry == null) {
                // 新建身份时才构造的原持仓名或当前导入的原文本
                String name = side == CorePositionSide.NET ? instrumentId : instrumentId + ':' + side.name();
                // 为未命中身份建立、尚待 putIfAbsent 竞争发布的新持仓项
                PositionEntry created = new PositionEntry(
                        new PositionIdentity(userId, name), instrumentId, side, false);
                entry = positions.putIfAbsent(new PositionMapKey(key), created);
                if (entry == null) entry = created;
            }
            if (!entry.matches(userId, instrumentId, side)) {
                throw new IllegalStateException("deterministic position identity collision");
            }
            // CAS 读取的当前发布保留计数，负数表示退休并需要重试
            int uses = entry.get();
            if (uses < 0) { Thread.onSpinWait(); continue; }
            if (!entry.compareAndSet(uses, Math.incrementExact(uses))) continue;
            ensurePositionIdentityRegistered(entry, key);
            lane.identityLookupScratch().positionEntries.put(key, entry);
            return key;
        }
    }

    /**
     * Owner 消费一份 Lane 发布结果，先保证分配已记账再减少引用；无引用或重复释放拒绝，不在这里删除身份。
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    public void releasePublishedPosition(long key) {
        assertOwner();
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(key);
        if (entry == null || entry.get() <= 0)
            throw new IllegalStateException("position publication has no retained identity");
        recordPositionAllocation(key, entry);
        if (entry.decrementAndGet() < 0) throw new IllegalStateException("position identity released twice");
    }

    /**
     * Owner 为尚未记账的持仓项登记分配序号与确定性键，递增游标和字典版本，再标记已记账。
     * @param key 待查询、登记或释放的确定性身份长整数键
     * @param entry 当前要登记、记账或检查的持仓身份项
     */
    private void recordPositionAllocation(long key, PositionEntry entry) {
        if (entry.allocationRecorded) return;
        trackAllocation(positionAllocationKeys, positionKeyAllocations, nextPositionKey++, key);
        dictionaryVersion = Math.incrementExact(dictionaryVersion);
        entry.allocationRecorded = true;
    }

    /**
     * Owner 按用户、原持仓名查询；非法用户或空白名称返回空，不分配。
     * @param userId 该身份所属用户
     * @param positionKey 用户原持仓名，非法或空白返回空
     */
    public Long findPositionKey(long userId, String positionKey) {
        assertOwner();
        if (userId <= 0 || positionKey == null || positionKey.isBlank()) return null;
        return findPositionIdentity(userId, positionKey);
    }

    /**
     * 把 Owner 持仓名查询的未命中转换为零。
     * @param userId 该身份所属用户
     * @param positionKey 要只读查询的用户原持仓名
     */
    long findPositionKeyValue(long userId, String positionKey) {
        // Owner 原持仓名查询得到的可空身份键，空值转换为零
        Long value = findPositionKey(userId, positionKey);
        return value == null ? 0 : value;
    }

    /**
     * 要求合约和方向存在，从合约取标的后只读查询已有持仓键。
     * @param userId 该身份所属用户
     * @param instrument 提供永久标的文本的合约配置
     * @param side 净仓、多头或空头持仓方向
     */
    public long findPositionKeyValue(long userId, CoreInstrument instrument, CorePositionSide side) {
        assertOwner();
        if (userId <= 0 || instrument == null || side == null) return 0;
        return findPositionKeyValue(userId, instrument.instrumentId(), side);
    }

    /**
     * 计算确定性键并核对反向身份；未知或不匹配返回零，不建立身份。
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    private long findPositionKeyValue(long userId, String instrumentId, CorePositionSide side) {
        // 当前用户身份的确定性长整数键
        long key = positionIdentityKey(userId, instrumentId, side);
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(key);
        return entry != null && entry.matches(userId, instrumentId, side) ? key : 0;
    }

    /**
     * Owner 返回下次分配的顺序号，供本次准备失败时回滚；它不是确定性持仓业务键。
     */
    public long positionCheckpoint() {
        assertOwner();
        return nextPositionKey;
    }

    /**
     * 从分配尾部撤销检查点之后的身份，跳过已释放项；仍被发布引用占用或双向表不一致时报错。回滚不降低字典版本，失败前可能已有部分撤销。
     * @param checkpoint 准备开始时保存的持仓分配顺序号
     */
    public void rollbackPositionKeys(long checkpoint) {
        assertOwner();
        if (checkpoint <= 0 || checkpoint > nextPositionKey) {
            throw new IllegalArgumentException("invalid position identity checkpoint");
        }
        while (nextPositionKey > checkpoint) {
            // 正在回滚或移除的 Owner 分配顺序号
            long allocation = --nextPositionKey;
            // 当前用户身份的确定性长整数键
            long key = positionAllocationKeys.removeKeyIfAbsent(allocation, 0);
            if (key == 0) continue;
            if (positionKeyAllocations.removeKeyIfAbsent(key, allocation) != allocation) {
                throw new IllegalStateException("position identity allocation index is inconsistent");
            }
            // 本次反向表查到或发布的持仓身份项
            PositionEntry entry = positionEntry(key);
            if (entry != null && !entry.compareAndSet(0, -1))
                throw new IllegalStateException("cannot roll back a Lane-owned position identity");
            // 当前比较、导入或哈希的用户范围身份
            PositionIdentity identity = entry == null ? null : entry.identity;
            if (identity == null || !Long.valueOf(key).equals(positionKeys.remove(identity))) {
                throw new IllegalStateException("position identity checkpoint is inconsistent");
            }
            entry.identityRegistered = false;
            ownerLookupScratch.positionEntries.remove(key);
            positions.remove(new PositionMapKey(key), entry);
        }
    }

    /**
     * 以用户和原持仓名生成确定性正键。
     * @param identity 包含用户与原持仓名的不可变身份
     */
    private static long deterministicPositionKey(PositionIdentity identity) {
        return deterministicKey(identity.userId(), identity.positionKey());
    }

    /**
     * 以用户和已构造的原持仓名计算键，不登记身份。
     * @param userId 该身份所属用户
     * @param name 用于计算确定性键的完整原持仓名
     */
    static long positionIdentityKey(long userId, String name) { return deterministicKey(userId, name); }

    /**
     * 净仓直接哈希标的，对冲仓按标的、冒号和方向的 UTF-8 字节流哈希；不必构造带方向的新字符串，结果保证正且非零。
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public static long positionIdentityKey(long userId, String instrumentId, CorePositionSide side) {
        if (side == CorePositionSide.NET) return deterministicKey(userId, instrumentId);
        // 按用户字节与文本流逐步累计的哈希
        long hash = 0xcbf29ce484222325L;
        for (/* 用户 long 按低位字节在前遍历的比特偏移 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
            hash = hashByte(hash, (int) (userId >>> shift & 0xffL));
        hash = hashUtf8(hash, instrumentId);
        hash = hashByte(hash, ':');
        // 方向枚举的固定 ASCII 名称
        String sideName = side.name();
        for (/* 当前数组、用户字节或字符流的遍历位置 */ int i = 0; i < sideName.length(); i++) hash = hashByte(hash, sideName.charAt(i));
        hash &= Long.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    /**
     * 只查已登记的原持仓名身份，缺失时报错，不在结算读路径临时分配。
     * @param userId 该身份所属用户
     * @param positionKey 必须已登记的用户原持仓名
     */
    public long preparedPositionKey(long userId, String positionKey) {
        // 当前用户身份的确定性长整数键
        Long key = findPositionIdentity(userId, positionKey);
        if (key == null) throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    /**
     * 按标的和方向查已有持仓键，并核对反向身份；缺失或不匹配时报错。
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public long preparedPositionKey(long userId, String instrumentId, CorePositionSide side) {
        // 当前用户身份的确定性长整数键
        long key = positionIdentityKey(userId, instrumentId, side);
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(key);
        if (entry == null || !entry.matches(userId, instrumentId, side)) {
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        }
        return key;
    }

    /**
     * 核对订单所属 Lane，用订单预存的持仓身份键及标的、方向查已准备身份，不重建持仓名。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param order 所属 Lane 中的订单，已保存持仓身份键、标的编号和方向
     */
    public long preparedPositionKey(AccountLaneState lane, OrderRuntime order) {
        lane.assertOwner();
        // 从当前订单或身份项读出的用户
        long userId = order.userId();
        if (lane.laneId() != clientTopology.accountLaneId(userId))
            throw new IllegalStateException("position identity crossed account Lane");
        // 当前用户身份的确定性长整数键
        long key = order.positionIdentityKey();
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(lane, key);
        if (entry == null || !entry.matches(userId, order.instrument().instrumentId(), order.positionSide()))
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    /**
     * 核对 Lane 和用户归属，再按标的、方向读取已准备持仓身份；缺失时报错。
     * @param lane 实际持有该用户账户身份的 Lane，显式入口核对路由和运行时实例
     * @param userId 该身份所属用户
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    public long preparedPositionKey(AccountLaneState lane, long userId, String instrumentId, CorePositionSide side) {
        lane.assertOwner();
        if (lane.laneId() != clientTopology.accountLaneId(userId))
            throw new IllegalStateException("position identity crossed account Lane");
        // 当前用户身份的确定性长整数键
        long key = positionIdentityKey(userId, instrumentId, side);
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(lane, key);
        if (entry == null || !entry.matches(userId, instrumentId, side))
            throw new IllegalStateException("position identity was not prepared by the Sequencer");
        return key;
    }

    /**
     * 直接核对净仓原名或对冲仓的标的前缀、冒号、方向后缀，不分配拼接字符串。
     * @param actual 反向身份保存的完整原持仓名
     * @param instrumentId 永久数字标的身份；整数重载直接给数值，文本重载使用规范数字文本
     * @param side 净仓、多头或空头持仓方向
     */
    private static boolean positionNameEquals(String actual, String instrumentId, CorePositionSide side) {
        if (side == CorePositionSide.NET) return actual.equals(instrumentId);
        // 方向枚举的固定 ASCII 名称
        String sideName = side.name();
        // 标的前缀长度，用来直接核对对冲持仓名的冒号及方向后缀
        int prefixLength = instrumentId.length();
        if (actual.length() != prefixLength + 1 + sideName.length()
                || actual.charAt(prefixLength) != ':') return false;
        if (!actual.regionMatches(0, instrumentId, 0, prefixLength)) return false;
        return actual.regionMatches(prefixLength + 1, sideName, 0, sideName.length());
    }

    /**
     * 按实际持仓键反查原持仓名，同时核对该身份属于指定用户；缺失或跨用户拒绝。
     * @param userId 该身份所属用户
     * @param positionKey 要反查原持仓名的确定性整数键
     */
    public String positionKey(long userId, long positionKey) {
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(positionKey);
        // 当前比较、导入或哈希的用户范围身份
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null || identity.userId() != userId) {
            throw new IllegalArgumentException("unknown runtime position key: " + userId + '/' + positionKey);
        }
        return identity.positionKey();
    }

    /**
     * 按确定性持仓键反查用户和原持仓名；未知身份拒绝。
     * @param positionKey 必须已存在的确定性持仓整数键
     */
    public PositionIdentity positionIdentity(long positionKey) {
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(positionKey);
        // 当前比较、导入或哈希的用户范围身份
        PositionIdentity identity = entry == null ? null : entry.identity;
        if (identity == null) {
            throw new IllegalArgumentException("unknown runtime position key: " + positionKey);
        }
        return identity;
    }

    /**
     * 读取已记录身份分配的版本值；释放不倒退，不能把它当成当前存活身份数。
     */
    public long dictionaryVersion() {
        return dictionaryVersion;
    }

    /**
     * Owner 汇总当前绑定 Lane 或独立冷表中的存活客户端身份数，统计身份条目而不是保留引用总数。
     */
    public int clientIdentityCount() {
        assertOwner();
        // 汇总的存活客户端身份条目数，不是引用总数
        int count = 0;
        if (clientRuntime != null) {
            for (/* 新旧冷运行时账户 Lane 的遍历下标 */ int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            // 用户所属账户 Lane 或当前循环正迁移的 Lane
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
count = Math.addExact(count, lane.clientIdentities().publishedCount());
        }
        } else if (unboundClients != null) {
            // 遍历分配或客户端身份表的原始游标
            var iterator = unboundClients.values().iterator();
            while (iterator.hasNext()) count = Math.addExact(count, iterator.next().publishedCount());
        }
        return count;
    }

    /**
     * Owner 仅在引用数为零时把持仓项标记退休，再移除正向、反向和分配记录；未知或仍被保留的项直接跳过，游标和版本不倒退。
     * @param positionKey 可以尝试退休的确定性持仓整数键
     */
    public void releasePositionKey(long positionKey) {
        assertOwner();
        // 本次反向表查到或发布的持仓身份项
        PositionEntry entry = positionEntry(positionKey);
        if (entry == null || !entry.compareAndSet(0, -1)) return;
        // 先移除旧正向身份，再让新一代身份有机会取得同一个确定性键。
        positionKeys.remove(entry.identity, positionKey);
        entry.identityRegistered = false;
        ownerLookupScratch.positionEntries.remove(positionKey);
        positions.remove(new PositionMapKey(positionKey), entry);
        removeAllocation(positionAllocationKeys, positionKeyAllocations, positionKey);
    }

    /**
     * Owner 导出资产、规范标的、存活客户端和持仓身份；客户端与持仓快照游标按当前条目数加一编码，不直接复制历史分配顺序号。
     */
    public Snapshot snapshot() {
        assertOwner();
        // 本次快照从各客户端表导出的存活身份映射
        Map<ClientIdentity, Long> clientKeys = new HashMap<>(clientIdentityCount());
        if (clientRuntime != null) {
            for (/* 新旧冷运行时账户 Lane 的遍历下标 */ int laneIndex = 0; laneIndex < clientRuntime.accountLaneCount(); laneIndex++) {
            // 用户所属账户 Lane 或当前循环正迁移的 Lane
            AccountLaneState lane = clientRuntime.accountLane(laneIndex);
lane.clientIdentities().exportTo(clientKeys);
        }
        } else if (unboundClients != null) unboundClients.forEachValue(/* 当前用户或迁移中的客户端身份表 */ table -> table.exportTo(clientKeys));
        // 本次快照以永久数字文本为键导出的标的字典
        Map<String, Integer> exportedSymbols = new HashMap<>(symbolIds.size());
        symbolIds.forEachKeyValue((/* 标的字典保存的外部永久正整数 ID */ permanentId, /* 该永久标的对应的内部稠密编号 */ runtimeId) -> exportedSymbols.put(symbols[runtimeId], runtimeId));
        return new Snapshot(assetIds, exportedSymbols, clientKeys, positionKeys,
                nextAssetId, nextSymbolId, clientKeys.size() + 1L, positionKeys.size() + 1L);
    }

    /**
     * 按用户低位字节在前的八字节和原文本 UTF-8 流计算确定性哈希，最后保留正值且把零改成一；实际哈希碰撞仍由使用身份的表核对。
     * @param userId 该身份所属用户
     * @param value 参与 UTF-8 流哈希的原身份文本
     */
    private static long deterministicKey(long userId, String value) {
        // 按用户字节与文本流逐步累计的哈希
        long hash = 0xcbf29ce484222325L;
        for (/* 用户 long 按低位字节在前遍历的比特偏移 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ (userId >>> shift & 0xffL)) * 0x100000001b3L;
        }
        hash = hashUtf8(hash, value);
        // 当前用户身份的确定性长整数键
        long key = hash & Long.MAX_VALUE;
        return key == 0 ? 1 : key;
    }

    /**
     * 逐个字符合成 UTF-8 字节并更新哈希，支持代理对；孤立代理项按 Java UTF-8 编码的问号处理，不创建临时字节数组。
     * @param hash 前面字节累计得到的哈希状态
     * @param value 按 Java UTF-8 规则逐字符读取的原文本
     */
    private static long hashUtf8(long hash, String value) {
        // 按 Java UTF-8 编码逐字节计算，避免为每次身份查询创建 byte[]。
        // 非法代理项与 String.getBytes(UTF_8) 一致，编码为 '?'，保持历史身份键不变。
        for (/* 当前数组、用户字节或字符流的遍历位置 */ int i = 0; i < value.length(); i++) {
            // 当前 UTF-16 代码单元，用来决定 UTF-8 编码分支
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
                // 由有效代理对合成的 Unicode 码点
                int point = Character.toCodePoint(c, value.charAt(++i));
                hash = hashByte(hash, 0xf0 | (point >>> 18));
                hash = hashByte(hash, 0x80 | ((point >>> 12) & 0x3f));
                hash = hashByte(hash, 0x80 | ((point >>> 6) & 0x3f));
                hash = hashByte(hash, 0x80 | (point & 0x3f));
            } else hash = hashByte(hash, '?');
        }
        return hash;
    }

    /**
     * 将一个低八位字节混入 FNV 风格哈希；long 乘法溢出是原哈希算法的环绕行为。
     * @param hash 前面字节累计得到的哈希状态
     * @param value 只取低八位参与计算的字节值
     */
    private static long hashByte(long hash, int value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /**
     * 把反向文本写到给定编号；容量足够时复用原数组，不足则按二倍精确扩容并复制，由调用方重新发布返回的数组。
     * @param values 已有反向身份文本数组
     * @param id 内部身份编号，用作反向数组下标
     * @param value 要存到指定编号的反向身份文本
     */
    private static String[] storeIdentity(String[] values, int id, String value) {
        // 本次写入的反向数组，原容量足够则直接复用
        String[] target = values;
        if (id >= target.length) {
            // 反向身份数组扩容到足以容纳当前编号的目标容量
            int capacity = target.length;
            while (capacity <= id) capacity = Math.multiplyExact(capacity, 2);
            target = java.util.Arrays.copyOf(target, capacity);
        }
        target[id] = value;
        return target;
    }

    /**
     * 同时登记分配序号到确定性键及反向关系；任一键已存在即拒绝。
     * @param allocations 分配顺序号到实际身份键的表
     * @param allocationsByKey 实际身份键到分配顺序号的表
     * @param allocation 本次 Owner 记账使用的分配顺序号
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private static void trackAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                        long allocation, long key) {
        if (allocations.containsKey(allocation) || allocationsByKey.containsKey(key)) {
            throw new IllegalStateException("runtime identity allocation collision");
        }
        allocations.put(allocation, key);
        allocationsByKey.put(key, allocation);
    }

    /**
     * 先从业务键反查并移除分配号，再核对并删除正向记录；没有记录直接返回，不一致时报错且不自动恢复此前删除。
     * @param allocations 分配顺序号到实际身份键的表
     * @param allocationsByKey 实际身份键到分配顺序号的表
     * @param key 待查询、登记或释放的确定性身份长整数键
     */
    private static void removeAllocation(LongLongHashMap allocations, LongLongHashMap allocationsByKey,
                                         long key) {
        // 正在回滚或移除的 Owner 分配顺序号
        long allocation = allocationsByKey.removeKeyIfAbsent(key, 0);
        if (allocation == 0) return;
        if (allocations.removeKeyIfAbsent(allocation, key) != key) {
            throw new IllegalStateException("runtime identity allocation index is inconsistent");
        }
    }

    /**
     * 从快照重建独立冷身份表和反向数组，导入客户端身份、以零发布引用恢复持仓项；恢复游标并按各游标推算字典版本，不启动工作线程。
     * @param snapshot 创建和恢复边界提供的身份快照
     */
    public static RuntimeIdentityRegistry restore(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("identity snapshot is required");
        // 从快照重建的独立冷身份表
        RuntimeIdentityRegistry registry = new RuntimeIdentityRegistry();
        snapshot.assetIds().forEach((/* 新建身份时才构造的原持仓名或当前导入的原文本 */ name, /* 当前内部身份编号，或恢复回调提供的内部编号 */ id) -> {
            registry.assetIds.put(name, id);
            registry.assets = storeIdentity(registry.assets, id, name);
        });
        snapshot.symbolIds().forEach((/* 新建身份时才构造的原持仓名或当前导入的原文本 */ name, /* 当前内部身份编号，或恢复回调提供的内部编号 */ id) -> {
            registry.symbolIds.put(com.surprising.product.api.utils.InstrumentIds.parse(name), id);
            registry.symbols = storeIdentity(registry.symbols, id, name);
        });
        snapshot.clientKeys().forEach((/* 当前比较、导入或哈希的用户范围身份 */ identity, /* 当前用户身份的确定性长整数键 */ key) -> {
            registry.clientTable(identity.userId()).importIdentity(key, identity.userId(), identity.clientOrderId());
        });
        snapshot.positionKeys().forEach((/* 当前比较、导入或哈希的用户范围身份 */ identity, /* 当前用户身份的确定性长整数键 */ key) -> {
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

    /**
     * 身份恢复数据和游标。只读包装不保证底层数据全被复制，创建及持有期间须遵守快照边界；不保存未消费发布结果的引用数。
     * @param assetIds 资产文本到内部编号的快照映射
     * @param symbolIds 规范永久数字标的文本到内部编号的快照映射
     * @param clientKeys 用户客户端单号到实际身份键的存活映射
     * @param positionKeys 用户持仓名到确定性键的存活映射
     * @param nextAssetId 快照中的下次资产编号
     * @param nextSymbolId 快照中的下次内部标的编号
     * @param nextClientKey 客户端快照游标，须大于存活条目数
     * @param nextPositionKey 持仓快照游标，须大于存活条目数，恢复后用于 Owner 分配记账
     */
    public record Snapshot(Map<String, Integer> assetIds, Map<String, Integer> symbolIds,
                           Map<ClientIdentity, Long> clientKeys, Map<PositionIdentity, Long> positionKeys,
                           int nextAssetId, int nextSymbolId, long nextClientKey, long nextPositionKey) {
        /**
         * 包装只读映射并检查游标与条目数的基本关系；有序资产、标的映射及客户端、持仓映射仍借用原底层数据，并非全部深拷贝。
         */
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

    /**
     * 用户范围内的原客户端单号值，不修剪文本；记录本身不额外校验参数。
     * @param userId 该身份所属用户
     * @param clientOrderId 用户原始客户端单号，空白单号按相应入口的零键规则处理
     */
    public record ClientIdentity(long userId, String clientOrderId) implements Comparable<ClientIdentity> {
        /**
         * 先按用户数字，再按原客户端单号字典序排序。
         * @param other 按当前键或身份规则比较的另一对象
         */
        @Override
        public int compareTo(ClientIdentity other) {
            // 先比较两个用户 ID，只有相同才比较原客户端单号
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : clientOrderId.compareTo(other.clientOrderId);
        }
    }

    /**
     * 用户范围内的原持仓名值；净仓为标的，对冲仓还含方向，记录本身不额外校验参数。
     * @param userId 该身份所属用户
     * @param positionKey 用户范围的原持仓名文本
     */
    public record PositionIdentity(long userId, String positionKey) implements Comparable<PositionIdentity> {
        /**
         * 先按用户数字，再按原持仓名字典序排序。
         * @param other 按当前键或身份规则比较的另一对象
         */
        @Override
        public int compareTo(PositionIdentity other) {
            // 先比较两个用户 ID，只有相同才比较原持仓名
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : positionKey.compareTo(other.positionKey);
        }
    }
}
