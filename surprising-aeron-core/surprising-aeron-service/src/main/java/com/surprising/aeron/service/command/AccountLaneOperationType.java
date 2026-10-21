package com.surprising.aeron.service.command;

/** 说明一项账户 Lane 任务属于哪种工作，便于派发、统计和检查任务的执行边界。 */
public enum AccountLaneOperationType {
    /** 普通账户命令，例如余额调整、账户设置或其他由命令发起的账户变更。 */
    COMMAND,
    /** 应用撮合或生命周期处理产生的资金、订单和持仓结算变化。 */
    SETTLEMENT,
    /** 在账户所属 Lane 内读取数据，不以查询名义绕过线程所有权。 */
    QUERY,
    /** 风险估值和风险状态处理，仍在账户自己的 Lane 中执行。 */
    RISK
}
