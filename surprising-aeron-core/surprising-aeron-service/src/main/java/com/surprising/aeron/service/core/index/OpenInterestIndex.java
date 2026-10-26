package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

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
            throw new IllegalStateException("position business index requires bound owner thread");
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
     * @param instrumentId 持仓所属的外部合约编号
     */
    public long openInterestSteps(String instrumentId) {
        assertBusinessOwner();
        // 当前合约的索引自有累计桶，只读取其多空数字，不交给查询调用方修改。
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : Math.max(value.longQuantity, value.shortQuantity);
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
     * 把一条业务持仓的非零数量贡献加入所属合约的多头或空头绝对总量。
     * @param position 需要增加数量贡献的业务持仓
     */
    private void add(CorePositionState position) {
        // 当前单仓的带方向数量，0 对总量没有贡献。
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), longQuantity(quantity), shortQuantity(quantity));
    }

    /**
     * 零差额直接返回；首次累计禁止负贡献，已有合约精确加减后禁止总量变负。合法时原位覆盖总量，即使归零也保留桶供复用。
     * @param instrumentId 持仓所属的外部合约编号
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
