package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.query.QueryTooLargeException;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.position.Position;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 账本持有的 ADL 持仓业务索引。按结算资产维护非空仓候选成员，并保存独立可复用索引值，避免保留账户变更槽的借用对象。绑定业务线程后，索引遍历和更新都在该线程执行。 */
public final class AdlPositionIndex {
    /** 该索引绑定的账本业务访问线程，null 表示尚未启用线程限制。 */
    private Thread businessOwner;
    /** 绑定此业务索引的访问线程，后续受保护的读写都按线程身份检查。未绑定时可在启动准备或恢复构建索引。 */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /** 绑定业务线程后拒绝其他线程直接遍历或改写索引，避免 Owner 或账户 Lane 越过账本索引边界。 */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires bound owner thread");
    }

    private final Map<String, LongHashSet> keysByAsset = new HashMap<>();
    /** 按整数持仓键保存的索引自有条目，包括保留的空仓值，不能当作账户持仓权威表。 */
    private final LongObjectHashMap<IndexedPosition> positions = new LongObjectHashMap<>();

    /** 从业务状态的用户持仓重建资产候选成员及索引自有条目，使用同一身份表生成整数持仓键。 */
    public AdlPositionIndex(TradingCoreState state, IdIndex identities) {
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            apply(positionKey, null, user.userId(), position.instrumentId(), position.marginAsset(),
                    position.positionSide(), position.signedQuantitySteps());
        }));

    }

    /** 先规范资产代码并检查候选数量上限，再复制当前有效成员为只读路由，按用户、合约和持仓腿排序，返回保持该顺序的不可修改集合。 */
    public Set<PositionKey> positions(String asset, int maximumPositions) {
        assertBusinessOwner();
        LongHashSet values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > maximumPositions)
            throw new QueryTooLargeException();
        PositionKey[] captured = new PositionKey[values.size()];
        int[] cursor = {0};
        values.forEach(positionKey -> {
            IndexedPosition value = positions.get(positionKey);
            if (value != null) captured[cursor[0]++] = new PositionKey(
                    value.userId(), value.instrumentId(), value.positionSide());
        });
        PositionKey[] ordered = cursor[0] == captured.length
                ? captured : java.util.Arrays.copyOf(captured, cursor[0]);
        java.util.Arrays.sort(ordered);
        LinkedHashSet<PositionKey> result = new LinkedHashSet<>(ordered.length);
        Collections.addAll(result, ordered);
        return Collections.unmodifiableSet(result);
    }

    /** 读取索引自己持有的当前条目引用，供同一业务线程随后应用变更时作为精确旧值校验。 */
    public IndexedPosition value(long positionKey) {
        assertBusinessOwner();
        return positions.get(positionKey);
    }

    /** 从账户发布的持仓索引值提取本次字段，转到字段更新入口。 */
    void apply(long positionKey, IndexedPosition previous, Position.IndexValue current) {
        apply(positionKey, previous, current == null ? 0 : current.userId(),
                current == null ? null : current.instrumentId(), current == null ? null : current.asset(),
                current == null ? null : current.positionSide(), current == null ? 0 : current.signedQuantitySteps());
    }

    /** 要求旧值引用就是本索引当前条目，先根据旧非空仓和新数量、资产调整成员集合，再删除或原位更新索引自有条目。 */
    void apply(long positionKey, IndexedPosition previous, long userId, String instrumentId, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        assertBusinessOwner();
        if (positions.get(positionKey) != previous)
            throw new IllegalStateException("ADL position index differs from shared position index");
        boolean previousActive = previous != null && previous.signedQuantitySteps() != 0;
        boolean currentActive = userId != 0 && quantity != 0;
        boolean membershipChanged = previousActive != currentActive
                || (previousActive && !previous.asset().equals(asset));
        if (membershipChanged && previousActive) remove(previous, positionKey);
        if (membershipChanged && currentActive)
            keysByAsset.computeIfAbsent(asset, ignored -> new LongHashSet()).add(positionKey);
        if (userId == 0) { positions.remove(positionKey); }
        else {
            IndexedPosition entry = previous;
            if (entry == null) {
                entry = new IndexedPosition();
                positions.put(positionKey, entry);
            }
            entry.userId = userId; entry.instrumentId = instrumentId; entry.asset = asset;
            entry.positionSide = side; entry.signedQuantitySteps = quantity;
        }
    }

    /** 索引自身持有的可变条目，只复制用户、合约、资产、持仓腿和带方向数量。写线程可原位更新，不能把此条目直接交账户 Lane 当持仓权威状态。 */
    static final class IndexedPosition {
        private long userId, signedQuantitySteps;
        private String instrumentId, asset;
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private com.surprising.aeron.protocol.CorePositionSide positionSide;
        /** 返回该索引条目对应的用户编号。 */
        long userId() { return userId; }
        /** 返回最近一次发布的带方向数量步数，0 表示保留记录但不在候选成员内。 */
        long signedQuantitySteps() { return signedQuantitySteps; }
        /** 返回该条目所属的外部合约编号，供查询复制路由。 */
        String instrumentId() { return instrumentId; }
        /** 返回当前结算资产代码，候选成员按它分组。 */
        String asset() { return asset; }
    }

    private void remove(IndexedPosition position, long positionKey) {
        if (position.signedQuantitySteps() == 0) return;
        LongHashSet values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(positionKey);
    }

    /** 查询阶段复制出的只读 ADL 持仓路由，只包含用户、外部合约编号和持仓腿，后续账户 Lane 仍需读取并校验实际持仓。 */
    public record PositionKey(long userId, String instrumentId, com.surprising.aeron.protocol.CorePositionSide positionSide)
            implements Comparable<PositionKey> {
        /** 依次按用户编号、合约编号字符串、持仓腿枚举顺序排序，使候选查询路由的遍历顺序确定。 */
        @Override
        public int compareTo(PositionKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare
                    : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
