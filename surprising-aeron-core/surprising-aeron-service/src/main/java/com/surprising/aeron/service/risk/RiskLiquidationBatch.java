package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**  Lane 产生的新增清算需求；owner 按 Lane 顺序分配连续编号后，交回原 Lane 落地。 构造约定：为一个 Lane 创建可复用的清算需求批次，实际数组等第一次出现需求时才分配。 参数 capacity：调用方为本次有限工作量片段设置的最大需求数量 */
@RequiredArgsConstructor
public final class RiskLiquidationBatch {
    /**  本片段工作上限；最多一个工作单元生成一条清算。  返回该批次允许保存的最大需求数量，复用时仍受此容量限制。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int capacity;
    /** 借用的 Lane 持仓引用，不在这里制作持仓副本；仅有新增清算时才分配有界数组。 */
    private PositionRuntime[] positions;
    /** 与 positions 同下标的触发价格序号；只有下标小于 count 的部分属于本批有效内容。 */
    private long[] priceSequences;
    /** Lane 写入数量，完成发布后由 owner 读取。 */
    private int count;
    /** owner 唯一写入的起始编号，派发后由 Lane 读取。 */
    private long firstId;

    /** 返回本批新增的清算需求数，Owner 在 Lane 结果发布后据此预留连续编号。 */
    int size() { return count; }

    /** 开始下一批时清零有效数量和编号起点，复用原数组；旧数组内容不清除，超出新 count 的部分不会被应用。 */
    public void reset() {
        count = 0;
        firstId = 0;
    }

    /**
     * Lane 保存需要新建强平计划的持仓引用及触发价格序号；超过工作预算立即报错，不扩大数组。
     * @param position 风险判断时的持仓记录，稍后用于生成完整强平计划
     * @param priceSequence 形成这份需求的行情序号
     */
    void add(PositionRuntime position, long priceSequence) {
        if (positions == null) {
            positions = new PositionRuntime[capacity];
            priceSequences = new long[capacity];
        }
        if (count == capacity) throw new IllegalStateException("risk creation budget exceeded");
        positions[count] = position;
        priceSequences[count++] = priceSequence;
    }

    /**
     * Owner 为本批预留从 nextId 开始的连续编号，返回下一批可用的起点；整数溢出直接报错。
     * @param nextId 全局当前尚未分配的强平编号起点
     */
    long assign(long nextId) {
        // 本批编号范围之后的第一个可用编号，用于 Owner 继续分配后续 Lane 的需求。
        long end = Math.addExact(nextId, count);
        firstId = nextId;
        return end;
    }

    /**
     * 原账户 Lane 使用 Owner 已分配的编号，把本批需求逐条落为 PLANNED 强平记录；每条先计划平掉对应持仓的全部绝对数量。
     * @param runtime 接收强平计划的运行时，调用方必须在正确的状态所有权范围内执行
     */
    void apply(TradingAccountRuntime runtime) {
        if (firstId <= 0) throw new IllegalStateException("risk liquidation IDs not assigned");
        for (/* 当前需要落地的需求下标，同时决定它相对本批起点的连续强平编号。 */ int i = 0; i < count; i++) {
            // 风险扫描时保存的持仓记录，提供用户、币对、保证金模式和带方向数量。
            PositionRuntime p = positions[i];
            runtime.putLiquidation(new LiquidationRuntime(firstId + i, p.userId(), p.symbolId(), p.marginMode(),
                    p.positionSide(), p.instrument(), priceSequences[i], p.signedQuantitySteps(),
                    Math.absExact(p.signedQuantitySteps()), 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
        }
    }
}
