package com.surprising.aeron.service.product;

import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.product.inverse.delivery.InverseDeliveryTradingRules;
import com.surprising.aeron.service.product.inverse.perpetual.InversePerpetualTradingRules;
import com.surprising.aeron.service.product.linear.delivery.LinearDeliveryTradingRules;
import com.surprising.aeron.service.product.linear.perpetual.LinearPerpetualTradingRules;
import com.surprising.aeron.service.product.option.OptionTradingRules;
import com.surprising.aeron.service.product.spot.SpotTradingRules;
import com.surprising.product.api.ProductLine;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 按产品线选取一套确定的交易规则，让现货、四类期货和期权使用各自的资金与结算口径。 共用的是不保存账户状态的规则对象，不是不同产品线的订单、余额或持仓。 构造约定： 只提供固定规则查询，不需要给每个请求创建新的注册表。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProductTradingRulesRegistry {

    /** 现货规则，买卖分别预留报价资产或基础资产。 */
    private static final ProductTradingRules SPOT = new SpotTradingRules();
    /** U 本位永续规则，使用线性合约盈亏、资金费和准入计算。 */
    private static final ProductTradingRules LINEAR_PERPETUAL = new LinearPerpetualTradingRules();
    /** 币本位永续规则，使用反向合约盈亏、资金费和准入计算。 */
    private static final ProductTradingRules INVERSE_PERPETUAL = new InversePerpetualTradingRules();
    /** U 本位交割规则，处理线性合约交易及到期或维护结算。 */
    private static final ProductTradingRules LINEAR_DELIVERY = new LinearDeliveryTradingRules();
    /** 币本位交割规则，处理反向合约交易及到期或维护结算。 */
    private static final ProductTradingRules INVERSE_DELIVERY = new InverseDeliveryTradingRules();
    /** 期权规则，区分权利金现金收支、卖方保证金和到期现金结算。 */
    private static final ProductTradingRules OPTION = new OptionTradingRules();

    /**
     * 根据币对真实合约类型选规则，并再次检查规则与币对匹配，防止跨产品使用。
     * @param instrument 本次交易或结算使用的币对配置，不能为 null
     * @return 与该币对产品线一致的共用规则对象
     */
    public static ProductTradingRules forInstrument(CoreInstrument instrument) {
        if (instrument == null) throw new IllegalArgumentException("settlement instrument is required");
        // 先按合约所属产品线选规则，再由规则自身核对合约类型，不能只信调用方的产品标记。
        ProductTradingRules kernel = forProductLine(instrument.contractType().productLine());
        kernel.requireInstrument(instrument);
        return kernel;
    }

    /**
     * 从六条产品线中选择固定规则；没有产品线就直接报错，不默认改用现货或另一类合约。
     * @param productLine 当前命令或运行时已经确定的产品线
     * @return 这条产品线专属的交易规则
     */
    public static ProductTradingRules forProductLine(ProductLine productLine) {
        if (productLine == null) throw new IllegalArgumentException("product line is required");
        return switch (productLine) {
            case SPOT -> SPOT;
            case LINEAR_PERPETUAL -> LINEAR_PERPETUAL;
            case INVERSE_PERPETUAL -> INVERSE_PERPETUAL;
            case LINEAR_DELIVERY -> LINEAR_DELIVERY;
            case INVERSE_DELIVERY -> INVERSE_DELIVERY;
            case OPTION -> OPTION;
        };
    }
}
