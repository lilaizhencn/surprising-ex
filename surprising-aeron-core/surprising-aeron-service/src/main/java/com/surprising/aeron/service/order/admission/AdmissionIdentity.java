package com.surprising.aeron.service.order.admission;

/**
 * 账户准入前已经准备好的身份值和借用的账本结算事实。Lane 用这些值检查订单与生命周期，不在逐笔检查中重新生成一组身份或账本副本。
 * @param clientKey 客户端订单标识的整数身份，没有已知标识时为 0
 * @param symbolId 币对的运行时稠密编号
 * @param positionKey 用户、币对和持仓腿对应的持仓身份，没有现存身份时可为 0
 * @param settlementStatus 准入前已固定的不可变结算状态事实，提供交割与资金费冲突标记
 */
public record AdmissionIdentity(
        long clientKey, int symbolId, long positionKey,
        com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus) {
    /**
     * 从本次借用的账本事实读取该合约是否已经完成到期结算。
     */
    public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
    /**
     * 从本次账本事实读取资金费持仓截面是否仍在处理中，准入据此拒绝冲突订单。
     */
    public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
}
