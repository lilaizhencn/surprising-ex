package com.surprising.aeron.service.settlement;

/** 资金分录的所有者与子账类别；真实分录由运行态资金变化保存。 */
public final class FundsPosting {
    private FundsPosting() {
    }

    /** 本条资金变化归谁：普通用户、做市账户、系统财库或系统外部。 */
    public enum OwnerKind {
        /**
         * 普通用户账户的资金归属。
         */
        USER,
        /**
         * 做市账户的资金归属。
         */
        MAKER,
        /**
         * 系统财库的资金归属，所有者编号为零。
         */
        TREASURY,
        /**
         * 系统外部的资金归属，编号为零且只使用外部调整子账。
         */
        EXTERNAL;

    }

    /** 按用途区分资金变化的子账类别；枚举本身不执行余额更新，也不校验一组分录是否平衡。 */
    public enum Subledger {
        /**
         * 可用余额的变动。
         */
        AVAILABLE,
        /**
         * 冻结余额的变动。
         */
        LOCKED,
        /**
         * 订单资金预留的变动。
         */
        RESERVATION,
        /**
         * 持仓保证金的变动。
         */
        POSITION_MARGIN,
        /**
         * 交易手续费的变动。
         */
        FEE,
        /**
         * 保险基金资金的变动。
         */
        INSURANCE,
        /**
         * 强平费用的变动。
         */
        LIQUIDATION_FEE,
        /**
         * 资金费结算剩余差额的变动。
         */
        FUNDING_RESIDUAL,
        /**
         * 金额舍入剩余差额的变动。
         */
        ROUNDING_RESIDUAL,
        /**
         * 清算盈亏资金的变动。
         */
        CLEARING_PNL,
        /**
         * 亏空资金的变动。
         */
        DEFICIT,
        /**
         * 外部调入或调出系统的资金变化，只配外部所有者。
         */
        EXTERNAL_ADJUSTMENT;

    }
}
