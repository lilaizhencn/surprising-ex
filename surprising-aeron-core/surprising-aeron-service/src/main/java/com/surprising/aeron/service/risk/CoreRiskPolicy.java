package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreRiskStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Core 内的风险等级判定规则。保证金率和风险限额来自带版本的币对配置， 这里仅把已经算出的风险占比映射为正常、预警或强平，并判断某种持仓是否允许进入强平。 构造约定： 规则没有实例状态，直接使用静态方法即可。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreRiskPolicy {

    /** 这套风险等级规则的版本编号，便于与币对风险参数版本分开识别。 */
    static final int VERSION = 1;
    /** 维持保证金占权益达到 80% 时进入预警，按百万分之一保存。 */
    static final long WARNING_MARGIN_RATIO_PPM = 800_000L;
    /** 维持保证金占权益达到 100% 时进入强平等级，按百万分之一保存。 */
    static final long LIQUIDATION_MARGIN_RATIO_PPM = 1_000_000L;

    /**
     * 空仓无需强平；当前支持的非组合保证金模型下，已全额支付权利金的期权多头也不进入保证金强平。
     * @param type 用来区分期权和其他产品的合约类型
     * @param quantity 带方向的持仓数量步数，正数多头、负数空头、0 为空仓
     */
    public static boolean canLiquidate(com.surprising.instrument.api.model.ContractType type, long quantity) {
        return quantity != 0 && !(type.isOption() && quantity > 0);
    }

    /**
     * 按包含边界的阈值判级：达到一百万为强平，否则达到八十万为预警，其余为正常。
     * @param ratioPpm 维持保证金相对权益的风险占比，按一百万倍保存，越大风险越高
     */
    public static CoreRiskStatus status(long ratioPpm) {
        return ratioPpm >= LIQUIDATION_MARGIN_RATIO_PPM ? CoreRiskStatus.LIQUIDATION
                : ratioPpm >= WARNING_MARGIN_RATIO_PPM ? CoreRiskStatus.WARNING : CoreRiskStatus.NORMAL;
    }
}
