package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
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

/**
 * 账本持有的 ADL 持仓业务索引。按结算资产维护非空仓候选成员，并保存独立可复用索引值，避免保留账户变更槽的借用对象；绑定业务线程后，索引遍历和更新都在该线程执行。
 */
public final class AdlPositionIndex {
    /** 该索引绑定的账本业务访问线程，null 表示尚未启用线程限制。 */
    private Thread businessOwner;
    /**
     * 绑定此业务索引的访问线程，后续受保护的读写都按线程身份检查；未绑定时可在启动准备或恢复构建索引。
     * @param thread 要绑定的索引业务访问线程，未绑定或传空时不做线程身份限制
     */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /**
     * 绑定业务线程后拒绝其他线程直接遍历或改写索引，避免 Owner 或账户 Lane 越过账本索引边界；尚未绑定时不限制。
     */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires bound owner thread");
    }

    /** 每种结算资产的非空仓整数持仓键集合，空集合保留以降低平仓重开时的分配。 */
    private final Map<String, LongHashSet> keysByAsset = new HashMap<>();
    /** 按整数持仓键保存的索引自有条目，包括保留的空仓值，不能当作账户持仓权威表。 */
    private final LongObjectHashMap<IndexedPosition> positions = new LongObjectHashMap<>();

    /**
     * 从业务状态的用户持仓重建资产候选成员及索引自有条目，使用同一身份表生成整数持仓键。
     * @param state 用于初次构建或恢复重建的完整业务状态
     * @param identities 业务持仓键到运行时整数键的映射，重建时共用它保持身份一致
     */
    public AdlPositionIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        state.users().values().forEach(/* 恢复状态中当前遍历的用户，从其持仓记录复制索引字段。 */ user -> user.positions().forEach((/* 该用户持仓映射里的业务键，交身份表生成对应整数持仓键。 */ key, /* 业务状态中的当前持仓，只提取索引所需身份、资产和数量字段。 */ position) -> {
            // 本轮候选成员或恢复记录对应的整数持仓键。
            long positionKey = identities.positionKey(user.userId(), key);
            apply(positionKey, null, user.userId(), position.instrumentId(), position.marginAsset(),
                    position.positionSide(), position.signedQuantitySteps());
        }));

    }

    /**
     * 先规范资产代码并检查候选数量上限，再复制当前有效成员为只读路由，按用户、合约和持仓腿排序，返回保持该顺序的不可修改集合。
     * @param asset 候选所属的结算资产代码，查询入口会规范化，字段更新入口沿用已发布代码
     * @param maximumPositions 本次允许复制成候选路由的最大资产成员数量，超出时在物化前拒绝
     */
    public Set<PositionKey> positions(String asset, int maximumPositions) {
        assertBusinessOwner();
        // 目标资产的非空仓持仓键集合，缺失或为空时直接返回无候选。
        LongHashSet values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > maximumPositions)
            throw new com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException();
        // 已经通过数量上限检查后才分配的候选路由数组，最多与成员集合等长。
        PositionKey[] captured = new PositionKey[values.size()];
        // 数组已复制条数的单元素游标，供集合遍历回调递增。
        int[] cursor = {0};
        values.forEach(/* 本轮候选成员或恢复记录对应的整数持仓键。 */ positionKey -> {
            // 该候选键当前索引值，存在时才复制用户、合约及持仓腿到路由数组。
            IndexedPosition value = positions.get(positionKey);
            if (value != null) captured[cursor[0]++] = new PositionKey(
                    value.userId(), value.instrumentId(), value.positionSide());
        });
        // 按实际捕获条数截取的路由数组，随后原地排序形成确定查询顺序。
        PositionKey[] ordered = cursor[0] == captured.length
                ? captured : java.util.Arrays.copyOf(captured, cursor[0]);
        java.util.Arrays.sort(ordered);
        // 按排序结果插入的路由集合，返回时包成不可修改集合。
        LinkedHashSet<PositionKey> result = new LinkedHashSet<>(ordered.length);
        Collections.addAll(result, ordered);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 读取索引自己持有的当前条目引用，供同一业务线程随后应用变更时作为精确旧值校验。
     * @param positionKey 目标持仓在共享身份表中的整数键
     */
    public IndexedPosition value(long positionKey) {
        assertBusinessOwner();
        return positions.get(positionKey);
    }

    /**
     * 从账户发布的持仓索引值提取本次字段，转到字段更新入口；输入为空时以用户编号 0 表示删除，不保留输入对象引用。
     * @param positionKey 目标持仓在共享身份表中的整数键
     * @param previous 本索引持有的精确旧条目引用，没有旧记录时为 null，不能传其他索引的同字段对象
     * @param current 账户发布的最新持仓索引字段，null 表示删除目标记录
     */
    void apply(long positionKey, IndexedPosition previous, RuntimePositionIndexValue current) {
        apply(positionKey, previous, current == null ? 0 : current.userId(),
                current == null ? null : current.instrumentId(), current == null ? null : current.asset(),
                current == null ? null : current.positionSide(), current == null ? 0 : current.signedQuantitySteps());
    }

    /**
     * 要求旧值引用就是本索引当前条目，先根据旧非空仓和新数量、资产调整成员集合，再删除或原位更新索引自有条目；新增时建立索引自有条目。
     * @param positionKey 目标持仓在共享身份表中的整数键
     * @param previous 本索引持有的精确旧条目引用，没有旧记录时为 null，不能传其他索引的同字段对象
     * @param userId 新索引值所属的用户编号，0 是删除记录的信号
     * @param instrumentId 目标持仓的外部合约编号
     * @param asset 候选所属的结算资产代码，查询入口会规范化，字段更新入口沿用已发布代码
     * @param side 新值所属的净持仓、多头腿或空头腿
     * @param quantity 最新带方向数量步数，0 表示空仓，应从候选成员移除
     */
    void apply(long positionKey, IndexedPosition previous, long userId, String instrumentId, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        assertBusinessOwner();
        if (positions.get(positionKey) != previous)
            throw new IllegalStateException("ADL position index differs from shared position index");
        // 旧索引条目是否存在且数量非零，决定是否已经属于某资产候选集合。
        boolean previousActive = previous != null && previous.signedQuantitySteps() != 0;
        // 新值是否表示仍存在的非空持仓，用户为 0 或数量为 0 都不进候选集合。
        boolean currentActive = userId != 0 && quantity != 0;
        // 有仓状态或有仓时的结算资产发生变化，必须先移除旧成员再按需加入新资产集合。
        boolean membershipChanged = previousActive != currentActive
                || (previousActive && !previous.asset().equals(asset));
        // 先按旧字段处理原资产成员关系，再原位覆盖同一个索引自有条目。
        if (membershipChanged && previousActive) remove(previous, positionKey);
        if (membershipChanged && currentActive)
            keysByAsset.computeIfAbsent(asset, /* 首次建立资产成员集合时的资产映射键，回调只负责分配一个空整数键集合。 */ ignored -> new LongHashSet()).add(positionKey);
        if (userId == 0) { positions.remove(positionKey); }
        else {
            // 本索引持有且可复用的条目，首次出现才新建，之后直接替换字段。
            IndexedPosition entry = previous;
            if (entry == null) {
                entry = new IndexedPosition();
                positions.put(positionKey, entry);
            }
            entry.userId = userId; entry.instrumentId = instrumentId; entry.asset = asset;
            entry.positionSide = side; entry.signedQuantitySteps = quantity;
        }
    }

    /**
     * 索引自身持有的可变条目，只复制用户、合约、资产、持仓腿和带方向数量。写线程可原位更新，不能把此条目直接交账户 Lane 当持仓权威状态。
     */
    static final class IndexedPosition {
        /**
         * userId：该条目所属的用户编号。
         * signedQuantitySteps：该条目最近发布的带方向数量，正数多头、负数空头，0 不参与 ADL 候选。
         */
        private long userId, signedQuantitySteps;
        /**
         * instrumentId：该持仓的外部合约编号，用于复制查询路由。
         * asset：最近发布的结算资产代码，用来维护资产成员关系。
         */
        private String instrumentId, asset;
        /**  该条目的净持仓或双向腿身份，查询路由要完整保留。 返回净持仓或双向持仓腿，用来区分同用户同合约的持仓身份。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private com.surprising.aeron.protocol.CorePositionSide positionSide;
        /**
         * 返回该索引条目对应的用户编号。
         */
        long userId() { return userId; }
        /**
         * 返回最近一次发布的带方向数量步数，0 表示保留记录但不在候选成员内。
         */
        long signedQuantitySteps() { return signedQuantitySteps; }
        /**
         * 返回该条目所属的外部合约编号，供查询复制路由。
         */
        String instrumentId() { return instrumentId; }
        /**
         * 返回当前结算资产代码，候选成员按它分组。
         */
        String asset() { return asset; }
    }

    /**
     * 从原资产的非空仓候选集合移除这个持仓键，保留空集合存储以供重新开仓复用，完整重建时再清空缓存。
     * @param position 需要从原资产候选成员中移除的旧索引值，先读它的资产与数量再更新条目
     * @param positionKey 目标持仓在共享身份表中的整数键
     */
    private void remove(IndexedPosition position, long positionKey) {
        if (position.signedQuantitySteps() == 0) return;
        // 目标资产的非空仓持仓键集合，缺失或为空时直接返回无候选。
        LongHashSet values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(positionKey);
        // 资产身份仍保留，成员集合为空也继续复用，以减少平仓后重开的分配。
        // 查询把空集合视为无候选，完整重建时会清理这些缓存。
    }

    /**
     * 查询阶段复制出的只读 ADL 持仓路由，只包含用户、外部合约编号和持仓腿，后续账户 Lane 仍需读取并校验实际持仓。
     * @param userId 新索引值所属的用户编号，0 是删除记录的信号
     * @param instrumentId 目标持仓的外部合约编号
     * @param positionSide 查询路由的净持仓或双向持仓腿
     */
    public record PositionKey(long userId, String instrumentId, com.surprising.aeron.protocol.CorePositionSide positionSide)
            implements Comparable<PositionKey> {
        /**
         * 依次按用户编号、合约编号字符串、持仓腿枚举顺序排序，使候选查询路由的遍历顺序确定。
         * @param other 需要按确定路由顺序比较的另一份持仓身份
         */
        @Override
        public int compareTo(PositionKey other) {
            // 两个查询路由用户编号的比较结果，不同用户直接决定顺序。
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            // 同用户下合约编号的字符串比较结果，相同合约才继续比较持仓腿。
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare
                    : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
