package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.query.QueryTooLargeException;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.position.Position;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

/** 账本持有的合约持仓用户路由索引。 */
public final class PositionUserIndex {
    /** 绑定的索引业务线程，绑定后受保护的读写不能由其他线程执行。 */
    private Thread businessOwner;
    /** 绑定索引业务访问线程，生产由账本维护。尚未绑定时允许启动准备或恢复重建。 */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /** 绑定线程后，拒绝其他线程直接读写该业务索引。 */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires bound owner thread");
    }

    // 按 Lane 保持整数用户列表有序， 在线续扫用二分查后继，避免每次复制装箱 TreeSet 或线性遍历哈希集合。
    private final LaneTopology topology;
    private final Map<String, LongArrayList[]> usersBySymbol = new HashMap<>();
    private final Map<String, LongIntHashMap> positionCountsBySymbol = new HashMap<>();

    /** 按当前拓扑配置从业务状态重建索引，包含各用户所有持仓记录，不因数量为 0 而跳过。 */
    public PositionUserIndex(TradingCoreState state) {
        this(state, CoreConfiguration.standaloneLaneTopology());
    }

    /** 要求明确的 Lane 拓扑，按该拓扑分组并从业务状态重建合约用户路由。 */
    public PositionUserIndex(TradingCoreState state, LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        rebuild(state);
    }

    /** 账本只读持仓记录计数索引，校验含空仓在内的总规模并返回有序用户编号。 */
    public long[] riskQueryUserIds(long userId, int maximumPositions) {
        assertBusinessOwner();
        if (userId < 0 || maximumPositions < 1) throw new IllegalArgumentException("invalid risk query route");
        TreeSet<Long> users = new TreeSet<>();
        long total = 0;
        for (LongIntHashMap counts : positionCountsBySymbol.values()) {
            long count = userId == 0 ? counts.sum() : counts.get(userId);
            total = Math.addExact(total, count);
            if (total > maximumPositions)
                throw new QueryTooLargeException();
            if (count == 0) continue;
            if (userId == 0) counts.forEachKey(users::add);
        }
        if (userId != 0) users.add(userId);
        long[] selected = new long[users.size()];
        int index = 0;
        for (long user : users) selected[index++] = user;
        return selected;
    }

    /** 分别二分查询每个 Lane 游标之后的第一用户，再选其中最小编号，找不到返回 0，不物化整份用户集合。 */
    public long higherUserId(String instrumentId, long cursorUserId) {
        assertBusinessOwner();
        LongArrayList[] lanes = usersBySymbol.get(InstrumentId.requireValid(instrumentId));
        if (lanes == null) return 0;
        long higher = 0;
        for (LongArrayList users : lanes) {
            long candidate = higherUserId(users, cursorUserId);
            if (candidate != 0 && (higher == 0 || candidate < higher)) higher = candidate;
        }
        return higher;
    }

    /** 校验指定 Lane 编号，只在这一条有序整数用户列表中查游标后继，避免遍历其他 Lane，末尾返回 0。 */
    public long higherUserId(String instrumentId, int accountLaneId, long cursorUserId) {
        assertBusinessOwner();
        if (accountLaneId < 0 || accountLaneId >= topology.accountLaneCount()) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        LongArrayList[] lanes = usersBySymbol.get(InstrumentId.requireValid(instrumentId));
        return lanes == null ? 0 : higherUserId(lanes[accountLaneId], cursorUserId);
    }

    private static long higherUserId(LongArrayList users, long cursorUserId) {
        if (users.isEmpty()) return 0;
        int index = users.binarySearch(cursorUserId);
        index = index >= 0 ? index + 1 : -index - 1;
        return index == users.size() ? 0 : users.get(index);
    }

    /** 只从持仓发布值提取新旧用户和合约身份，再按记录增删或迁移维护路由。 */
    void apply(Position.IndexValue previous, Position.IndexValue current) {
        applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(),
                current == null ? 0 : current.userId(), current == null ? null : current.instrumentId());
    }

    /** 新旧用户及合约相同则路由不变，否则先减旧合约记录引用数，再加新合约引用数。 */
    void applyRoutes(long previousUser, String previousSymbol, long currentUser, String currentSymbol) {
        assertBusinessOwner();
        if (previousUser == currentUser && (previousUser == 0 || previousSymbol.equals(currentSymbol))) return;
        if (previousUser != 0) removePosition(previousUser, previousSymbol);
        if (currentUser != 0) addPosition(currentUser, currentSymbol);
    }

    /** 返回按现有整数索引逐项取得游标后用户的惰性可遍历对象，避免先复制完整 TreeSet。迭代时仍使用索引并遵守其业务线程权限。 */
    public Iterable<Long> usersAfter(String instrumentId, long afterUserId) {
        String normalized = InstrumentId.requireValid(instrumentId);
        return () -> new java.util.Iterator<>() {
            private long next = higherUserId(normalized, afterUserId);
            /** 只检查当前保存的下一用户编号是否非零，不推进索引。 */
            public boolean hasNext() { return next != 0; }
            /** 取出当前下一用户，再从实际索引查其后继用于下次迭代。 */
            public Long next() {
                if (next == 0) throw new java.util.NoSuchElementException();
                long result = next;
                next = higherUserId(normalized, result);
                return result;
            }
        };
    }

    /** 清空合约用户列表及记录引用数，从全部用户持仓逐条重建，空仓和双向持仓腿都保留其记录计数。 */
    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        for (UserAccountSnapshot user : state.users().values()) {
            long userId = user.userId();
            for (CorePositionState position : user.positions().values()) {
                addPosition(userId, position.instrumentId());
            }
        }
    }

    /** 增加该用户在该合约的持仓记录数，仅从 0 变 1 时把用户插入其 Lane 的有序成员列表，避免双向腿重复用户。 */
    private void addPosition(long userId, String instrumentId) {
        LongIntHashMap counts = positionCountsBySymbol.computeIfAbsent(instrumentId, ignored -> new LongIntHashMap());
        int count = counts.addToValue(userId, 1);
        if (count == 1) add(instrumentId, userId);
    }

    private void removePosition(long userId, String instrumentId) {
        LongIntHashMap counts = positionCountsBySymbol.get(instrumentId);
        if (counts == null) throw new IllegalStateException("position user count is missing");
        int count = counts.addToValue(userId, -1);
        if (count == 0) {
            counts.removeKey(userId);
            if (counts.isEmpty()) positionCountsBySymbol.remove(instrumentId);
            remove(instrumentId, userId);
        } else if (count < 0) throw new IllegalStateException("negative position user count");
    }

    private void add(String instrumentId, long userId) {
        LongArrayList[] lanes = usersBySymbol.computeIfAbsent(instrumentId, ignored -> newLaneLists());
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) return;
        users.addAtIndex(-index - 1, userId);
    }

    private void remove(String instrumentId, long userId) {
        LongArrayList[] lanes = usersBySymbol.get(instrumentId);
        if (lanes == null) return;
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) users.removeAtIndex(index);
        boolean empty = true;
        for (LongArrayList lane : lanes) empty &= lane.isEmpty();
        if (empty) usersBySymbol.remove(instrumentId);
    }

    private LongArrayList[] newLaneLists() {
        LongArrayList[] lanes = new LongArrayList[topology.accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++) lanes[laneId] = new LongArrayList();
        return lanes;
    }
}
