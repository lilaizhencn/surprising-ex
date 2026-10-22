package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.AssetBalance;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 一条带正负号的资产变动，说明哪种所有者的哪本子账增加或减少了多少。只保存本条分录，不代表完整转账或资金守恒检查。
 * @param asset 本条变化的资产名称，构造时规范化
 * @param ownerKind 资产归属的所有者类别
 * @param ownerId 用户或做市账户的正数编号，系统财库及外部使用零
 * @param subledger 本条变化归入的资金子账
 * @param units 有符号的最小单位变化量，正数增加、负数减少，不允许零
 */
public record FundsPosting(String asset, OwnerKind ownerKind, long ownerId, Subledger subledger, long units) {

    /**
     * 检查资产、所有者与子账非空且金额非零，规范化资产名称；用户及做市账户编号须为正，系统及外部编号须为零，外部所有者必须且只能配外部调整子账。
     */
    public FundsPosting {
        if (asset == null || ownerKind == null || subledger == null || units == 0) {
            throw new IllegalArgumentException("invalid funds posting");
        }
        asset = AssetBalance.normalizeAsset(asset);
        // 本条分录是否归外部所有者，用于检查编号和外部调整子账是否匹配。
        boolean externalOwner = ownerKind == OwnerKind.EXTERNAL;
        if ((ownerKind == OwnerKind.USER || ownerKind == OwnerKind.MAKER) && ownerId <= 0
                || (ownerKind == OwnerKind.TREASURY || externalOwner) && ownerId != 0
                || externalOwner != (subledger == Subledger.EXTERNAL_ADJUSTMENT)) {
            throw new IllegalArgumentException("invalid funds posting owner");
        }
    }

    /** 本条资金变化归谁：普通用户、做市账户、系统财库或系统外部。 构造约定：保存所有者类别对应的固定编码。 参数 wireCode：给该枚举项指定的固定编码 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum OwnerKind {
        /**
         * 普通用户账户的资金归属。
         */
        USER(1),
        /**
         * 做市账户的资金归属。
         */
        MAKER(2),
        /**
         * 系统财库的资金归属，所有者编号为零。
         */
        TREASURY(3),
        /**
         * 系统外部的资金归属，编号为零且只使用外部调整子账。
         */
        EXTERNAL(4);

        /**  此枚举项的固定编码，独立于声明顺序。 返回该所有者类别的固定编码，不使用会受枚举排列影响的 ordinal。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final int wireCode;

    }

    /** 按用途区分资金变化的子账类别；枚举本身不执行余额更新，也不校验一组分录是否平衡。 构造约定：保存子账类别对应的固定编码。 参数 wireCode：给该枚举项指定的固定编码 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum Subledger {
        /**
         * 可用余额的变动。
         */
        AVAILABLE(1),
        /**
         * 冻结余额的变动。
         */
        LOCKED(2),
        /**
         * 订单资金预留的变动。
         */
        RESERVATION(3),
        /**
         * 持仓保证金的变动。
         */
        POSITION_MARGIN(4),
        /**
         * 交易手续费的变动。
         */
        FEE(5),
        /**
         * 保险基金资金的变动。
         */
        INSURANCE(6),
        /**
         * 强平费用的变动。
         */
        LIQUIDATION_FEE(7),
        /**
         * 资金费结算剩余差额的变动。
         */
        FUNDING_RESIDUAL(8),
        /**
         * 金额舍入剩余差额的变动。
         */
        ROUNDING_RESIDUAL(9),
        /**
         * 清算盈亏资金的变动。
         */
        CLEARING_PNL(10),
        /**
         * 亏空资金的变动。
         */
        DEFICIT(11),
        /**
         * 外部调入或调出系统的资金变化，只配外部所有者。
         */
        EXTERNAL_ADJUSTMENT(12);

        /**  此枚举项的固定编码，独立于声明顺序。 返回该子账的固定编码。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private final int wireCode;

    }
}
