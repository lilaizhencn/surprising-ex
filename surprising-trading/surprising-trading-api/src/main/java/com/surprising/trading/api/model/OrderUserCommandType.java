package com.surprising.trading.api.model;

/** 用户订单分区事实流支持的命令类型。 */
public enum OrderUserCommandType {
    PLACE,
    CANCEL,
    CANCEL_OPEN,
    PRUNE_REDUCE_ONLY,
    ALGO_PLACE,
    ALGO_UPDATE,
    ALGO_CHILD,
    ACCOUNT_RESULT,
    MATCH_RESULT
}
