package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.position.Position;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

/** 账本维护的每合约多空持仓总量索引，持仓发布时按旧值减、新值加的差额更新。 */
public final class OpenInterestIndex {
    /** 绑定的账本业务访问线程，未绑定时允许启动构建索引。 */
    private Thread businessOwner;
    /** 绑定索引业务访问线程，未绑定时可做启动准备和恢复构建。 */
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    /** 绑定线程后拒绝其他线程直接读写多空总量，未绑定时不限制访问。 */
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires bound owner thread");
    }

    private final Map<String, MutableTotals> totals = new HashMap<>();

    /** 从完整业务持仓直接重建每合约多空总量。 */
    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    /** 只复制多空任一侧非零的累计行，按外部合约编号排序后返回不可修改映射，索引自身累计对象不外泄。 */
    public NavigableMap<String, Totals> totals() {
        assertBusinessOwner();
        TreeMap<String, Totals> snapshot = new TreeMap<>();
        totals.forEach((instrumentId, value) -> {
            if (value.longQuantity != 0 || value.shortQuantity != 0)
                snapshot.put(instrumentId, new Totals(value.longQuantity, value.shortQuantity));
        });
        return Collections.unmodifiableNavigableMap(snapshot);
    }

    /** 校验合约编号并返回多头总量与空头绝对总量的较大值，没有记录时为 0。 */
    public long openInterestSteps(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(InstrumentId.requireValid(instrumentId));
        return value == null ? 0 : Math.max(value.longQuantity, value.shortQuantity);
    }

    /** 从新旧持仓索引值提取合约和带方向数量，空值按无对应持仓处理，再应用合约多空数量差额。 */
    void apply(Position.IndexValue previous, Position.IndexValue current) {
        applyQuantities(previous == null ? null : previous.instrumentId(), previous == null ? 0 : previous.signedQuantitySteps(),
                current == null ? null : current.instrumentId(), current == null ? 0 : current.signedQuantitySteps());
    }

    /** 同合约时一次调整新旧多空差额。 */
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

    /** 清空旧累计桶，遍历所有用户持仓按合约及数量方向重建，空仓直接跳过。 */
    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        totals.clear();
        state.users().values().forEach(user -> user.positions().values().forEach(this::add));
    }

    private void add(CorePositionState position) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), longQuantity(quantity), shortQuantity(quantity));
    }

    /** 首次累计禁止负贡献，已有合约精确加减后禁止总量变负。 */
    private void adjust(String instrumentId, long longDelta, long shortDelta) {
        if (longDelta == 0 && shortDelta == 0) return;
        MutableTotals current = totals.get(instrumentId);
        if (current == null) {
            if (longDelta < 0 || shortDelta < 0) {
                throw new IllegalStateException("open interest index is missing instrumentId=" + instrumentId);
            }
            totals.put(instrumentId, new MutableTotals(longDelta, shortDelta));
            return;
        }
        long longs = Math.addExact(current.longQuantity, longDelta);
        long shorts = Math.addExact(current.shortQuantity, shortDelta);
        if (longs < 0 || shorts < 0) {
            throw new IllegalStateException("open interest index is negative for instrumentId=" + instrumentId);
        }
        current.longQuantity = longs; current.shortQuantity = shorts;
    }

    private static long longQuantity(long signedQuantity) {
        return signedQuantity > 0 ? signedQuantity : 0;
    }

    /** 最小 long 无法取反时直接报溢出。 */
    private static long shortQuantity(long signedQuantity) {
        return signedQuantity < 0 ? Math.negateExact(signedQuantity) : 0;
    }

    /** 查询复制出的单合约只读多空总量，两个方向都保存非负绝对步数，不把空头写成负数。 */
    public record Totals(long longQuantity, long shortQuantity) {
    }

    /** 索引写线程持有的可变单合约累计值，更新时原位调整，查询时另复制成只读 Totals。构造约定：保存调用方已检查的多空初始绝对总量，作为索引线程可复用的累计桶。 */
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class MutableTotals {
        private long longQuantity;
        private long shortQuantity;

    }
}
