package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.config.CoreConfiguration;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

/**
 * 账本持有的合约持仓用户路由索引。按用户在同合约的持仓记录数维护成员，空仓记录也计数；用户按账户 Lane 分组放入有序整数列表，供风险扫描二分续取，不读取或复制账户余额及持仓实体。
 */
public final class PositionUserIndex {
    /** 绑定的索引业务线程，绑定后受保护的读写不能由其他线程执行。 */
    private Thread businessOwner;
    /**
     * 绑定索引业务访问线程，生产由账本维护；尚未绑定时允许启动准备或恢复重建。
     * @param thread 要绑定的业务访问线程，null 表示暂不检查线程身份
     */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /**
     * 绑定线程后，拒绝其他线程直接读写该业务索引；未绑定时不限制。
     */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires SystemLedgerLane");
    }

    // 用户成员变更少于行情和风险游标的读取。按 Lane 保持整数用户列表有序，
    // 在线续扫用二分查后继，避免每次复制装箱 TreeSet 或线性遍历哈希集合。
    /** 构建索引时固定的账户 Lane 拓扑，用于用户路由分组。 */
    private final LaneTopology topology;
    /** 每合约按 Lane 分组的有序整数用户列表，成员代表至少保留一份持仓记录。 */
    private final Map<String, LongArrayList[]> usersBySymbol = new HashMap<>();
    /** 每合约每用户持仓记录引用数，含零数量记录和双向腿，用来决定用户成员何时加入或移除。 */
    private final Map<String, LongIntHashMap> positionCountsBySymbol = new HashMap<>();

    /**
     * 按当前拓扑配置从业务状态重建索引，包含各用户所有持仓记录，不因数量为 0 而跳过。
     * @param state 初次构建或恢复重建所依据的完整业务状态
     */
    public PositionUserIndex(TradingCoreState state) {
        this.topology = CoreConfiguration.standaloneLaneTopology();
        rebuild(state);
    }

    /**
     * 使用当前配置的账户 Lane 拓扑，转到显式拓扑构造入口。
     * @param state 初次构建或恢复重建所依据的完整业务状态
     * @param identities 当前签名接收的身份映射；现有重建直接使用用户和外部合约编号，不使用此参数
     */
    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        this(state, identities,
                CoreConfiguration.standaloneLaneTopology());
    }

    /**
     * 要求明确的 Lane 拓扑，按该拓扑分组并从业务状态重建合约用户路由。
     * @param state 初次构建或恢复重建所依据的完整业务状态
     * @param identities 当前签名接收的身份映射；现有重建直接使用用户和外部合约编号，不使用此参数
     * @param topology 确定用户属于哪个账户 Lane 的拓扑，索引与实际运行时必须使用同一配置
     */
    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities, LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        rebuild(state, identities);
    }

    /**
     * 合并指定合约各 Lane 的既有用户编号为有序只读集合，供确实需要完整集合的查询使用；在线逐项扫描应使用后继接口。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     */
    public NavigableSet<Long> users(String instrumentId) {
        assertBusinessOwner();
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        if (lanes == null) return Collections.emptyNavigableSet();
        // 完整合约用户查询的临时去重排序集合，最后包成只读集合。
        TreeSet<Long> sorted = new TreeSet<>();
        for (LongArrayList /* 当前 Lane 的既有有序用户列表，逐项合并到完整查询结果。 */ users : lanes) users.forEach(sorted::add);
        return Collections.unmodifiableNavigableSet(sorted);
    }

    /**
     * 账本只读持仓记录计数索引，先校验含空仓在内的总规模，再选用户及涉及合约；用户 0 为全局范围，指定用户即使没有持仓也保留其路由供账户查询返回空结果。
     * @param userId 查询或维护的用户编号，风险查询路由中 0 表示全局范围
     * @param maximumPositions 风险查询允许的持仓记录总数上限，包含数量为 0 的记录，必须为正
     */
    public RiskQueryRoutes riskQueryRoutes(long userId, int maximumPositions) {
        assertBusinessOwner();
        if (userId < 0 || maximumPositions < 1) throw new IllegalArgumentException("invalid risk query route");
        // 本次风险查询涉及的去重用户有序集合。
        TreeSet<Long> users = new TreeSet<>();
        // 本次用户范围确实有持仓记录的合约编号有序集合。
        TreeSet<String> instruments = new TreeSet<>();
        // 本次查询范围内累计的持仓记录数，空仓也计入规模上限。
        long total = 0;
        for (var /* 当前合约对应的用户记录引用数映射项。 */ entry : positionCountsBySymbol.entrySet()) {
            // 当前合约的每用户持仓记录引用数，不能按非零持仓数量代替。
            LongIntHashMap counts = entry.getValue();
            // 当前合约在全局或指定用户范围内的持仓记录数量。
            long count = userId == 0 ? counts.sum() : counts.get(userId);
            total = Math.addExact(total, count);
            if (total > maximumPositions)
                throw new com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException();
            if (count == 0) continue;
            instruments.add(entry.getKey());
            if (userId == 0) counts.forEachKey(users::add);
        }
        if (userId != 0) users.add(userId);
        // 从有序用户集合复制的查询专用整数编号数组。
        long[] selected = new long[users.size()];
        // 当前用户在有序列表的二分位置，或查询结果数组的写入下标，按本步骤决定后继、插入或删除位置。
        int index = 0;
        for (long /* 当前输出到查询数组或恢复遍历到的用户，编号用于路由与计数。 */ user : users) selected[index++] = user;
        return new RiskQueryRoutes(selected, java.util.List.copyOf(instruments));
    }

    /**
     * 本次风险查询选中的用户编号数组及涉及的外部合约列表，仅承载路由和市场输入范围，不含持仓数量或资金副本。
     * @param userIds 本次查询选中的有序用户编号数组，由查询路由独立保存
     * @param instrumentIds 本次风险查询涉及的外部合约编号列表，用于 Owner 固定市场输入
     */
    public record RiskQueryRoutes(long[] userIds, java.util.List<String> instrumentIds) { }

    /**
     * 取得全合约严格大于游标的最小用户编号，将底层末尾标记 0 转为 null。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param cursorUserId 上次已经完整处理的用户编号，只返回比它更大的下一用户
     */
    public Long higherUser(String instrumentId, long cursorUserId) {
        // 当前查到的严格大于游标的最小用户编号，0 表示尚无后继。
        long higher = higherUserId(instrumentId, cursorUserId);
        return higher == 0 ? null : higher;
    }

    /**
     * 分别二分查询每个 Lane 游标之后的第一用户，再选其中最小编号，找不到返回 0，不物化整份用户集合。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param cursorUserId 上次已经完整处理的用户编号，只返回比它更大的下一用户
     */
    public long higherUserId(String instrumentId, long cursorUserId) {
        assertBusinessOwner();
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        if (lanes == null) return 0;
        // 当前查到的严格大于游标的最小用户编号，0 表示尚无后继。
        long higher = 0;
        for (LongArrayList /* 当前比较的一条 Lane 有序用户列表，分别取其后继后再做最小值选择。 */ users : lanes) {
            // 当前 Lane 在游标之后的第一用户编号，非零且更小时替换全局候选。
            long candidate = higherUserId(users, cursorUserId);
            if (candidate != 0 && (higher == 0 || candidate < higher)) higher = candidate;
        }
        return higher;
    }

    /**
     * 校验指定 Lane 编号，只在这一条有序整数用户列表中查游标后继，避免遍历其他 Lane，末尾返回 0。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param accountLaneId 只读取这一条账户 Lane 的有序用户列表，范围由拓扑校验
     * @param cursorUserId 上次已经完整处理的用户编号，只返回比它更大的下一用户
     */
    public long higherUserId(String instrumentId, int accountLaneId, long cursorUserId) {
        assertBusinessOwner();
        if (accountLaneId < 0 || accountLaneId >= topology.accountLaneCount()) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        return lanes == null ? 0 : higherUserId(lanes[accountLaneId], cursorUserId);
    }

    /**
     * 在一个已排序整数列表中二分游标，命中就取后一项，未命中就取插入点，列表末尾返回 0。
     * @param users 已经按编号升序排列的单 Lane 整数用户列表
     * @param cursorUserId 上次已经完整处理的用户编号，只返回比它更大的下一用户
     */
    private static long higherUserId(LongArrayList users, long cursorUserId) {
        if (users.isEmpty()) return 0;
        // 当前用户在有序列表的二分位置，或查询结果数组的写入下标，按本步骤决定后继、插入或删除位置。
        int index = users.binarySearch(cursorUserId);
        index = index >= 0 ? index + 1 : -index - 1;
        return index == users.size() ? 0 : users.get(index);
    }

    /**
     * 只从持仓发布值提取新旧用户和合约身份，再按记录增删或迁移维护路由；数量和方向变化不改变记录成员关系。
     * @param previous 变更前的持仓索引字段，null 表示之前没有该记录
     * @param current 变更后的持仓索引字段，null 表示该记录已删除
     */
    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(),
                current == null ? 0 : current.userId(), current == null ? null : current.instrumentId());
    }

    /**
     * 新旧用户及合约相同则路由不变，否则先减旧合约记录引用数，再加新合约引用数；用户 0 表示该侧没有记录。
     * @param previousUser 原记录所属用户编号，0 表示无旧记录
     * @param previousSymbol 原记录所属外部合约编号，无旧记录时可为空
     * @param currentUser 新记录所属用户编号，0 表示删除或无新记录
     * @param currentSymbol 新记录所属外部合约编号，无新记录时可为空
     */
    void applyRoutes(long previousUser, String previousSymbol, long currentUser, String currentSymbol) {
        assertBusinessOwner();
        if (previousUser == currentUser && (previousUser == 0 || previousSymbol.equals(currentSymbol))) return;
        if (previousUser != 0) removePosition(previousUser, previousSymbol);
        if (currentUser != 0) addPosition(currentUser, currentSymbol);
    }

    /**
     * 返回按现有整数索引逐项取得游标后用户的惰性可遍历对象，避免先复制完整 TreeSet；迭代时仍使用索引并遵守其业务线程权限。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param afterUserId 惰性遍历的起点，只产生严格大于它的用户编号
     */
    public Iterable<Long> usersAfter(String instrumentId, long afterUserId) {
        // 经过合约编号校验的遍历目标，迭代器后续使用同一编号查后继。
        String normalized = OrderReservation.requireInstrumentId(instrumentId);
        return () -> /* 本次惰性用户迭代器，只保存一个下一用户游标，依托实时有序索引逐项推进，不复制全部用户集合。 */ new java.util.Iterator<>() {
            /** 该惰性迭代器下一次要返回的用户编号，0 表示已经没有后续用户。 */
            private long next = higherUserId(normalized, afterUserId);
            /**
             * 只检查当前保存的下一用户编号是否非零，不推进索引。
             */
            public boolean hasNext() { return next != 0; }
            /**
             * 取出当前下一用户，再从实际索引查其后继用于下次迭代；已到末尾时抛出无更多元素异常。
             */
            public Long next() {
                if (next == 0) throw new java.util.NoSuchElementException();
                // 本次迭代应返回的用户编号，先保存再把 next 推进到其后继。
                long result = next;
                next = higherUserId(normalized, result);
                return result;
            }
        };
    }

    /**
     * 清空合约用户列表及记录引用数，从全部用户持仓逐条重建，空仓和双向持仓腿都保留其记录计数。
     * @param state 初次构建或恢复重建所依据的完整业务状态
     */
    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        for (CoreUserState /* 当前输出到查询数组或恢复遍历到的用户，编号用于路由与计数。 */ user : state.users().values()) {
            // 恢复时从当前用户记录取得的用户编号，用来累计各合约持仓引用数。
            long userId = user.userId();
            for (CorePositionState /* 恢复业务状态中的一条持仓记录，包含空仓也要为它维护路由引用数。 */ position : user.positions().values()) {
                addPosition(userId, position.instrumentId());
            }
        }
    }

    /**
     * 按同样规则重建全部持仓路由；保留身份表参数签名，但当前实现直接用外部合约编号和用户编号，不调用身份表分配键。
     * @param state 初次构建或恢复重建所依据的完整业务状态
     * @param identities 当前签名接收的身份映射；现有重建直接使用用户和外部合约编号，不使用此参数
     */
    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        assertBusinessOwner();
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        for (CoreUserState /* 当前输出到查询数组或恢复遍历到的用户，编号用于路由与计数。 */ user : state.users().values()) {
            // 恢复时从当前用户记录取得的用户编号，用来累计各合约持仓引用数。
            long userId = user.userId();
            for (CorePositionState /* 恢复业务状态中的一条持仓记录，包含空仓也要为它维护路由引用数。 */ position : user.positions().values()) {
                addPosition(userId, position.instrumentId());
            }
        }
    }

    /**
     * 增加该用户在该合约的持仓记录数，仅从 0 变 1 时把用户插入其 Lane 的有序成员列表，避免双向腿重复用户。
     * @param userId 查询或维护的用户编号，风险查询路由中 0 表示全局范围
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     */
    private void addPosition(long userId, String instrumentId) {
        // 当前合约的每用户持仓记录引用数，不能按非零持仓数量代替。
        LongIntHashMap counts = positionCountsBySymbol.computeIfAbsent(instrumentId, /* 首次建立合约计数或 Lane 列表时的外部合约映射键，回调只分配空容器。 */ ignored -> new LongIntHashMap());
        // 新增这份记录后的用户引用数，从 0 变 1 才需添加用户成员。
        int count = counts.addToValue(userId, 1);
        if (count == 1) add(instrumentId, userId);
    }

    /**
     * 减少一份持仓记录引用数，归零时移除该用户的合约路由，并清空没有任何用户的计数映射；缺少原计数或计数为负说明索引不一致。
     * @param userId 查询或维护的用户编号，风险查询路由中 0 表示全局范围
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     */
    private void removePosition(long userId, String instrumentId) {
        // 当前合约的每用户持仓记录引用数，不能按非零持仓数量代替。
        LongIntHashMap counts = positionCountsBySymbol.get(instrumentId);
        if (counts == null) throw new IllegalStateException("position user count is missing");
        // 删除这份记录后的用户引用数，归零时才能移除用户成员。
        int count = counts.addToValue(userId, -1);
        if (count == 0) {
            counts.removeKey(userId);
            if (counts.isEmpty()) positionCountsBySymbol.remove(instrumentId);
            remove(instrumentId, userId);
        } else if (count < 0) throw new IllegalStateException("negative position user count");
    }

    /**
     * 按拓扑定位用户 Lane，在该合约的有序整数列表中二分插入用户，已经存在时不重复添加。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param userId 查询或维护的用户编号，风险查询路由中 0 表示全局范围
     */
    private void add(String instrumentId, long userId) {
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = usersBySymbol.computeIfAbsent(instrumentId, /* 首次建立合约计数或 Lane 列表时的外部合约映射键，回调只分配空容器。 */ ignored -> newLaneLists());
        // 该用户所属 Lane 的有序整数用户列表，新增或删除都用二分定位。
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        // 当前用户在有序列表的二分位置，或查询结果数组的写入下标，按本步骤决定后继、插入或删除位置。
        int index = users.binarySearch(userId);
        if (index >= 0) return;
        users.addAtIndex(-index - 1, userId);
    }

    /**
     * 从该合约对应 Lane 的有序列表移除用户；所有 Lane 都空时再移除合约用户列表。
     * @param instrumentId 需要读取或维护用户路由的外部合约编号
     * @param userId 查询或维护的用户编号，风险查询路由中 0 表示全局范围
     */
    private void remove(String instrumentId, long userId) {
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = usersBySymbol.get(instrumentId);
        if (lanes == null) return;
        // 该用户所属 Lane 的有序整数用户列表，新增或删除都用二分定位。
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        // 当前用户在有序列表的二分位置，或查询结果数组的写入下标，按本步骤决定后继、插入或删除位置。
        int index = users.binarySearch(userId);
        if (index >= 0) users.removeAtIndex(index);
        // 移除用户后该合约的所有 Lane 是否都为空，全空才回收这组合约列表。
        boolean empty = true;
        for (LongArrayList /* 当前检查是否为空的一条 Lane 用户列表。 */ lane : lanes) empty &= lane.isEmpty();
        if (empty) usersBySymbol.remove(instrumentId);
    }

    /**
     * 为一个首次出现的合约创建每 Lane 一份空整数有序列表，后续更新只维护对应 Lane。
     */
    private LongArrayList[] newLaneLists() {
        // 该合约已有的各 Lane 有序用户列表，首次出现合约时才创建整组列表。
        LongArrayList[] lanes = new LongArrayList[topology.accountLaneCount()];
        for (/* 为新合约初始化空用户列表时的 Lane 下标。 */ int laneId = 0; laneId < lanes.length; laneId++) lanes[laneId] = new LongArrayList();
        return lanes;
    }
}
