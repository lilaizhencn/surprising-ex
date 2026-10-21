package com.surprising.aeron.service.business.option;

import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** 行情更新和风险检查共用的期权价格校验；期权必须有指数价及同到期远期价，其他产品不受这条规则限制。 */
public final class OptionRiskRules {
    /** 只提供校验规则，不创建保存状态的实例。 */
    private OptionRiskRules() {
    }

    /**
     * 仅对期权检查两个标的风险价格是否为正，缺失时返回业务拒绝，不修改币对配置或行情。
     * @param instrument 用于判断当前合约是否为期权的币对配置
     * @param indexPriceTicks 标的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    public static void requireOptionRiskPrices(CoreInstrument instrument, long indexPriceTicks,
                                               long forwardPriceTicks) {
        if (instrument.contractType().isOption() && (indexPriceTicks <= 0 || forwardPriceTicks <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
    }
}
