package com.surprising.aeron.service.state.index;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 账本维护的每合约多空持仓总量索引，持仓发布时按旧值减、新值加的差额更新。查询未平仓规模采用多空两边总量的较大值；零总量条目可保留复用，完整输出时不展示零行。
 */
public final class OpenInterestIndex {
    /** 绑定的账本业务访问线程，未绑定时允许启动构建索引。 */
    private Thread businessOwner;
    /**
     * 绑定索引业务访问线程，未绑定时可做启动准备和恢复构建。
     * @param thread 要绑定的业务访问线程，null 表示不做线程身份限制
     */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /**
     * 绑定线程后拒绝其他线程直接读写多空总量，未绑定时不限制访问。
     */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires SystemLedgerLane");
    }

    /** 按外部合约编号保存的可复用多空累计桶，含曾有仓但当前归零的条目。 */
    private final Map<String, MutableTotals> totals = new HashMap<>();

    /**
     * 从完整业务持仓直接重建每合约多空总量。
     * @param state 用于初次构建或恢复重建的完整业务状态
     */
    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    /**
     * 通过带身份表的构造签名重建多空总量，当前仍按外部合约编号聚合。
     * @param state 用于初次构建或恢复重建的完整业务状态
     * @param identities 当前重建签名保留的身份表参数；此实现按外部合约编号聚合，不使用它
     */
    public OpenInterestIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    /**
     * 只复制多空任一侧非零的累计行，按外部合约编号排序后返回不可修改映射，索引自身累计对象不外泄。
     */
    public NavigableMap<String, Totals> totals() {
        assertBusinessOwner();
        // 本次查询复制的外部合约有序映射，只有非零总量会加入。
        TreeMap<String, Totals> snapshot = new TreeMap<>();
        totals.forEach((/* 本轮需要复制查询行的外部合约编号。 */ instrumentId, /* 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。 */ value) -> {
            if (value.longQuantity != 0 || value.shortQuantity != 0)
                snapshot.put(instrumentId, new Totals(value.longQuantity, value.shortQuantity));
        });
        return Collections.unmodifiableNavigableMap(snapshot);
    }

    /**
     * 校验合约编号并返回多头总量与空头绝对总量的较大值，没有记录时为 0；不把多空相加重复统计同一规模。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     */
    public long openInterestSteps(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : Math.max(value.longQuantity, value.shortQuantity);
    }

    /**
     * 校验外部合约编号后读取多头绝对总量，没有记录时为 0。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     */
    public long longQuantity(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.longQuantity;
    }

    /**
     * 直接用调用方已规范化的合约编号读取多头总量，不重复做编号校验。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     */
    public long longQuantityNormalized(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.longQuantity;
    }

    /**
     * 校验外部合约编号后读取空头的正绝对数量总和，没有记录时为 0。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     */
    public long shortQuantity(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.shortQuantity;
    }

    /**
     * 直接用已规范化的合约编号读取空头绝对总量，不重复做编号校验。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     */
    public long shortQuantityNormalized(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.shortQuantity;
    }

    /**
     * 从新旧持仓索引值提取合约和带方向数量，空值按无对应持仓处理，再应用合约多空数量差额。
     * @param previous 变更前的精简持仓索引值，null 表示无旧贡献
     * @param current 变更后的精简持仓索引值，null 表示删除旧贡献
     */
    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        applyQuantities(previous == null ? null : previous.instrumentId(), previous == null ? 0 : previous.signedQuantitySteps(),
                current == null ? null : current.instrumentId(), current == null ? 0 : current.signedQuantitySteps());
    }

    /**
     * 同合约时一次调整新旧多空差额；合约变更或记录增删时分别移除旧数量、加入新数量，数量为 0 不贡献任一方向。
     * @param previousSymbol 旧持仓所属外部合约编号，无旧记录时为空
     * @param previousQuantity 旧持仓的带方向数量步数
     * @param currentSymbol 新持仓所属外部合约编号，无新记录时为空
     * @param currentQuantity 新持仓的带方向数量步数
     */
    void applyQuantities(String previousSymbol, long previousQuantity, String currentSymbol, long currentQuantity) {
        assertBusinessOwner();
        if (previousSymbol != null && previousSymbol.equals(currentSymbol)) {
            adjust(currentSymbol, longQuantity(currentQuantity) - longQuantity(previousQuantity),
                    shortQuantity(currentQuantity) - shortQuantity(previousQuantity));
            return;
        }
        if (previousSymbol != null) adjust(previousSymbol, -longQuantity(previousQuantity), -shortQuantity(previousQuantity));
        if (currentSymbol != null) adjust(currentSymbol, longQuantity(currentQuantity), shortQuantity(currentQuantity));
    }

    /**
     * 清空旧累计桶，遍历所有用户持仓按合约及数量方向重建，空仓直接跳过。
     * @param state 用于初次构建或恢复重建的完整业务状态
     */
    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        totals.clear();
        state.users().values().forEach(/* 业务状态中当前遍历的用户，恢复时从其所有持仓累加总量。 */ user -> user.positions().values().forEach(this::add));
    }

    /**
     * 清空后从业务持仓提取精简索引值再重建；当前按用户和外部合约编号计算，不使用传入身份表。
     * @param state 用于初次构建或恢复重建的完整业务状态
     * @param identities 当前重建签名保留的身份表参数；此实现按外部合约编号聚合，不使用它
     */
    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        assertBusinessOwner();
        totals.clear();
        state.users().values().forEach(/* 业务状态中当前遍历的用户，恢复时从其所有持仓累加总量。 */ user -> user.positions().forEach((/* 用户持仓映射的原业务键，本次数量聚合不需要这个键。 */ key, /* 本轮恢复遍历的业务持仓，按带方向数量贡献多头或空头绝对量。 */ position) -> {
            // 为当前业务持仓提取的精简字段，后续只使用其中合约和数量贡献。
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            add(indexed);
        }));
    }

    /**
     * 把一条业务持仓的非零数量贡献加入所属合约的多头或空头绝对总量。
     * @param position 需要增加或移除数量贡献的业务持仓或精简索引值
     */
    private void add(CorePositionState position) {
        // 当前单仓的带方向数量，0 对总量没有贡献。
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), longQuantity(quantity), shortQuantity(quantity));
    }

    /**
     * 从所属合约总量中扣掉这一业务持仓的贡献，空仓不需要调整。
     * @param position 需要增加或移除数量贡献的业务持仓或精简索引值
     */
    private void remove(CorePositionState position) {
        // 当前单仓的带方向数量，0 对总量没有贡献。
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), -longQuantity(quantity), -shortQuantity(quantity));
    }

    /**
     * 把精简索引值交统一数量更新方法，选择增加贡献。
     * @param position 需要增加或移除数量贡献的业务持仓或精简索引值
     */
    private void add(RuntimePositionIndexValue position) {
        update(position, true);
    }

    /**
     * 把精简索引值交统一数量更新方法，选择移除原贡献。
     * @param position 需要增加或移除数量贡献的业务持仓或精简索引值
     */
    private void remove(RuntimePositionIndexValue position) {
        update(position, false);
    }

    /**
     * 按增删方向把非零持仓换成多头和空头绝对数量差额，再统一调整对应合约累计值。
     * @param position 需要增加或移除数量贡献的业务持仓或精简索引值
     * @param add true 增加当前持仓贡献，false 从原总量中扣除
     */
    private void update(RuntimePositionIndexValue position, boolean add) {
        // 当前单仓的带方向数量，0 对总量没有贡献。
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        // 增加贡献取 1，删除贡献取负 1，分别乘到多空绝对数量上。
        long direction = add ? 1 : -1;
        adjust(position.instrumentId(), Math.multiplyExact(longQuantity(quantity), direction),
                Math.multiplyExact(shortQuantity(quantity), direction));
    }

    /**
     * 零差额直接返回；首次累计禁止负贡献，已有合约精确加减后禁止总量变负。合法时原位覆盖总量，即使归零也保留桶供复用。
     * @param instrumentId 读取或更新的外部合约编号，Normalized 入口要求调用方已完成规范化
     * @param longDelta 本次多头绝对总量的带正负变化额
     * @param shortDelta 本次空头绝对总量的带正负变化额
     */
    private void adjust(String instrumentId, long longDelta, long shortDelta) {
        if (longDelta == 0 && shortDelta == 0) return;
        // 该合约当前累计桶，不存在时只能从非负贡献创建新桶。
        MutableTotals current = totals.get(instrumentId);
        if (current == null) {
            if (longDelta < 0 || shortDelta < 0) {
                throw new IllegalStateException("open interest index is missing instrumentId=" + instrumentId);
            }
            totals.put(instrumentId, new MutableTotals(longDelta, shortDelta));
            return;
        }
        // 应用本次差额后的多头绝对总量，必须非负且不溢出。
        long longs = Math.addExact(current.longQuantity, longDelta);
        // 应用本次差额后的空头绝对总量，必须非负且不溢出。
        long shorts = Math.addExact(current.shortQuantity, shortDelta);
        if (longs < 0 || shorts < 0) {
            throw new IllegalStateException("open interest index is negative for instrumentId=" + instrumentId);
        }
        current.longQuantity = longs; current.shortQuantity = shorts;
        // 每个曾遇到的已注册合约保留一个累计桶；归零后复用桶，但查询不输出零行。
    }

    /**
     * 只保留带方向数量的正部分，空头或空仓对多头总量贡献为 0。
     * @param signedQuantity 单个持仓带多空符号的原始数量步数
     */
    private static long longQuantity(long signedQuantity) {
        return signedQuantity > 0 ? signedQuantity : 0;
    }

    /**
     * 把带方向数量的负部分精确取反为正绝对量，多头或空仓贡献为 0；最小 long 无法取反时直接报溢出。
     * @param signedQuantity 单个持仓带多空符号的原始数量步数
     */
    private static long shortQuantity(long signedQuantity) {
        return signedQuantity < 0 ? Math.negateExact(signedQuantity) : 0;
    }

    /**
     * 查询复制出的单合约只读多空总量，两个方向都保存非负绝对步数，不把空头写成负数。
     * @param longQuantity 该合约多头的非负绝对数量步数总和
     * @param shortQuantity 该合约空头的非负绝对数量步数总和
     */
    public record Totals(long longQuantity, long shortQuantity) {
    }

    /** 索引写线程持有的可变单合约累计值，更新时原位调整，查询时另复制成只读 Totals。 构造约定：保存调用方已检查的多空初始绝对总量，作为索引线程可复用的累计桶。 参数 longQuantity：该合约多头的非负绝对数量步数总和 参数 shortQuantity：该合约空头的非负绝对数量步数总和 */
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class MutableTotals {
        /** 当前合约多头非负绝对步数合计。 */
        private long longQuantity;
        /** 当前合约空头非负绝对步数合计。 */
        private long shortQuantity;

    }
}
