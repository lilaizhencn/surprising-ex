package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import java.util.HashMap;

/** Owner-confined batch execution state; reused only after terminal commit. */
final class BatchAdmissionOrderIndex
        implements com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex {
    /** 已提交活跃订单索引，作为批内准入计算的基线。 */
    final ActiveOrderIndex baseline;
    /** 字符串与 primitive 标识的唯一字典；生命周期覆盖该运行时。 */
    private final RuntimeIdentityRegistry identities;
    /** 本批用户，禁止把批内准入变化用于其他用户。 */
    long batchUserId;
    /** 仅本批产生的币对准入增量，退出批量范围即清空。 */
    final HashMap<String, SymbolAdmissionDelta> deltasBySymbol;
    /** 复用的准入检查结果，不在批量项之间分配临时结果对象。 */
    final com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary summary =
            new com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary();

    BatchAdmissionOrderIndex(ActiveOrderIndex baseline, RuntimeIdentityRegistry identities, int expectedOrders) {
        this.identities = identities;
        this.baseline = java.util.Objects.requireNonNull(baseline, "baseline");
        deltasBySymbol = new HashMap<>(Math.max(1, expectedOrders));
    }

    void reset(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("batch user is required");
        batchUserId = userId;
        deltasBySymbol.clear();
    }

    void clear() {
        batchUserId = 0;
        deltasBySymbol.clear();
    }

    void update(OrderRuntime previous, OrderRuntime current) {
        apply(previous, -1);
        apply(current, 1);
    }

    @Override
    public void admitted(long userId, ResolvedPlaceOrder order) {
        if (userId != batchUserId) throw new IllegalArgumentException("batch admission crossed user boundary");
        delta(order.symbol()).add(order.positionSide().ordinal(), order.side().ordinal(),
                order.marginMode().ordinal(), order.reduceOnly(), order.quantitySteps(), 1);
    }

    @Override
    public com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary inspect(
            long userId, String symbol,
            com.surprising.aeron.protocol.CorePositionSide positionSide,
            com.surprising.aeron.protocol.CoreOrderSide side,
            com.surprising.aeron.protocol.CoreMarginMode conflictingMarginMode) {
        var baselineSummary = baseline.inspect(
                userId, symbol, positionSide, side, conflictingMarginMode);
        if (userId != batchUserId) return summary.set(baselineSummary.pendingQuantity(),
                baselineSummary.reduceOnlyQuantity(), baselineSummary.marginModeCount());
        SymbolAdmissionDelta delta = deltasBySymbol.get(symbol);
        if (delta == null) return summary.set(baselineSummary.pendingQuantity(),
                baselineSummary.reduceOnlyQuantity(), baselineSummary.marginModeCount());
        return summary.set(
                Math.addExact(baselineSummary.pendingQuantity(),
                        delta.pending[positionSide.ordinal()][side.ordinal()]),
                Math.addExact(baselineSummary.reduceOnlyQuantity(), delta.reduceOnly[side.ordinal()]),
                Math.addExact(baselineSummary.marginModeCount(),
                        delta.marginModes[positionSide.ordinal()][conflictingMarginMode.ordinal()]));
    }

    void apply(OrderRuntime order, int direction) {
        if (order == null || order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN
                || order.userId() != batchUserId) return;
        delta(identities.symbol(order.symbolId())).add(order.positionSide().ordinal(), order.side().ordinal(),
                order.marginMode().ordinal(), order.reduceOnly(), order.remainingQuantitySteps(), direction);
    }

    SymbolAdmissionDelta delta(String symbol) {
        return deltasBySymbol.computeIfAbsent(symbol, ignored -> new SymbolAdmissionDelta());
    }

    final class SymbolAdmissionDelta {
        /** 按持仓方向和订单方向记录的本批待成交数量变化。 */
        final long[][] pending = new long[CorePositionSide.values().length][CoreOrderSide.values().length];
        /** 按买卖方向记录的本批只减仓数量变化。 */
        final long[] reduceOnly = new long[CoreOrderSide.values().length];
        /** 按持仓方向和保证金模式记录的订单数量变化。 */
        final int[][] marginModes =
                new int[CorePositionSide.values().length][CoreMarginMode.values().length];

        void add(int positionSide, int side, int marginMode, boolean reducing,
                         long quantitySteps, int direction) {
            long signedQuantity = direction > 0 ? quantitySteps : Math.negateExact(quantitySteps);
            if (reducing) reduceOnly[side] = Math.addExact(reduceOnly[side], signedQuantity);
            else pending[positionSide][side] = Math.addExact(pending[positionSide][side], signedQuantity);
            marginModes[positionSide][marginMode] = Math.addExact(
                    marginModes[positionSide][marginMode], direction);
        }
    }

}
