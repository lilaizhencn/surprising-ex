package com.surprising.aeron.service.state.admission;

import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 一次挂单准入检查的可复用汇总结果，只存数量和冲突条数。由所属执行线程填充并立即使用，不保存订单实体，也不提供独立权威状态。
 */
public final class AdmissionSummary {
    /**  准入检查采用的同向非只减仓待成交数量，供预估持仓规模及所需预留使用。 返回准入检查采用的同向非只减仓待成交数量，供预估持仓规模及所需预留使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long pendingQuantity;
    /**  同向只减仓挂单占用的数量，用于计算还可新增的平仓容量。 返回同向只减仓挂单占用的数量，用于计算还可新增的平仓容量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long reduceOnlyQuantity;
    /**  指定冲突保证金模式下的挂单条数，用于阻止仍有委托时切换模式。 返回指定冲突保证金模式下的挂单条数，用于阻止仍有委托时切换模式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int marginModeCount;




    /**
     * 一次写入当前准入检查的三项汇总并返回自身，下一次检查可以覆盖复用。
     * @param pendingQuantity 准入检查采用的同向非只减仓待成交数量，供预估持仓规模及所需预留使用
     * @param reduceOnlyQuantity 同向只减仓挂单占用的数量，用于计算还可新增的平仓容量
     * @param marginModeCount 指定冲突保证金模式下的挂单条数，用于阻止仍有委托时切换模式
     */
    public AdmissionSummary set(long pendingQuantity, long reduceOnlyQuantity, int marginModeCount) {
        this.pendingQuantity = pendingQuantity;
        this.reduceOnlyQuantity = reduceOnlyQuantity;
        this.marginModeCount = marginModeCount;
        return this;
    }
}
