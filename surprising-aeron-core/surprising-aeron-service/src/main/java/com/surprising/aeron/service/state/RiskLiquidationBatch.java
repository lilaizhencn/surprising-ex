package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreLiquidationState;

/** Lane 产生的新增清算需求；owner 按 Lane 顺序分配连续编号后，交回原 Lane 落地。 */
final class RiskLiquidationBatch {
    /** 本片段工作上限；最多一个工作单元生成一条清算。 */
    private final int capacity;
    /** 不可变持仓引用和固定风险输入，仅有新增清算时才分配有界数组。 */
    private PositionRuntime[] positions;
    private long[] instrumentChanges, priceSequences;
    /** Lane 写入数量，完成发布后由 owner 读取。 */
    private int count;
    /** owner 唯一写入的起始编号，派发后由 Lane 读取。 */
    private long firstId;

    RiskLiquidationBatch(int capacity) { this.capacity = capacity; }
    int size() { return count; }

    void add(PositionRuntime position, long instrumentChange, long priceSequence) {
        if (positions == null) {
            positions = new PositionRuntime[capacity];
            instrumentChanges = new long[capacity];
            priceSequences = new long[capacity];
        }
        if (count == capacity) throw new IllegalStateException("risk creation budget exceeded");
        positions[count] = position;
        instrumentChanges[count] = instrumentChange;
        priceSequences[count++] = priceSequence;
    }

    long assign(long nextId) {
        long end = Math.addExact(nextId, count);
        firstId = nextId;
        return end;
    }

    void apply(TradingRuntimeState runtime) {
        if (firstId <= 0) throw new IllegalStateException("risk liquidation IDs not assigned");
        for (int i = 0; i < count; i++) {
            PositionRuntime p = positions[i];
            runtime.putLiquidation(new LiquidationRuntime(firstId + i, p.userId(), p.symbolId(), p.marginMode(),
                    p.positionSide(), instrumentChanges[i], priceSequences[i], p.signedQuantitySteps(),
                    Math.absExact(p.signedQuantitySteps()), 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
        }
    }
}
