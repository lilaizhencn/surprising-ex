package com.surprising.aeron.service.command.order;

/** 标记一个订单批次要执行的业务类型，批次执行器据此选择准入、撮合和结果汇总方式。 */
public enum OrderBatchKind {
    /** 批量下单；每一项都要检查订单规则和可用资金或平仓容量。 */
    PLACE,
    /** 批量撤单；按撮合结果确认哪些订单已撤销，再释放对应预留。 */
    CANCEL,
    /** 批量改单；逐项处理原订单与修改后订单的数量、价格和预留差异。 */
    AMEND
}
