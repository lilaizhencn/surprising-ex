package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreArithmetic;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证合约计算中的风险分档、期权买卖方差异、取整及大整数中间值，避免金额公式在边界处少算保证金。 */
class CoreContractMathTest {

    /** 期权买方保证金为 0，应在计算巨大权利金之前返回，不能被无关乘法溢出阻断。 */
    @Test
    void optionBuyerZeroMarginDoesNotEvaluateOverflowingPremium() {
        // 使用乘数为 2 的看涨期权，让后面的极大价格一旦参与权利金乘法就会溢出。
        CoreInstrument instrument = CoreInstrument.from(ProductLine.OPTION,
                new RegisterInstrumentCommand("6", ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 2, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.CALL.ordinal(), 100));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.BUY, Long.MAX_VALUE, 2, 100_000, 0, 0)).isZero();
        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.BUY, Long.MAX_VALUE, 2, 0, 0)).isZero();
    }

    /** 当前规模决定保证金档位；已有持仓超出最高档上限仍可估值，新开仓严格选档则必须拒绝超限。 */
    @Test
    void maintenanceMarginUsesTheInstrumentRiskBracketForCurrentNotional() {
        // 两个保证金率不同的风险档位，用来区分小规模、大规模及超出最高金额上限的情况。
        CoreInstrument instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1",
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0,
                        10_000_000, 10_000, 0, 1,
                        List.of(
                                new CoreRiskLimitBracket(1, 0, 1_000, 10_000_000, 100_000, 200_000),
                                new CoreRiskLimitBracket(2, 1_000, 10_000, 5_000_000, 200_000, 500_000))));

        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 5, 100, 0, 0)).isEqualTo(100);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 20, 100, 0, 0)).isEqualTo(1_000);
        assertThat(CoreContractMath.openingMarginUnits(instrument, CoreOrderSide.BUY, 100, 20, 0, 0))
                .isEqualTo(400);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, 200, 100, 0, 0)).isPositive();
        assertThat(CoreContractMath.riskBracket(instrument, 10_000).bracketNo()).isEqualTo(2);
        assertThatThrownBy(() -> CoreContractMath.riskBracket(instrument, 10_001))
                .isInstanceOf(CoreStateRejectedException.class)
                .hasMessageContaining("exceeds instrument risk brackets");
    }

    /** 看涨期权卖方保证金应结合指数价、远期虚值程度、当前权利金及档位系数，并保留空头市值的负号。 */
    @Test
    void optionShortMarginUsesIndexForwardOtmAndCurrentPremium() {
        // 行权价 100、保证金调整系数 1.2 的看涨期权，供手工核对保证金与市值结果。
        CoreInstrument instrument = CoreInstrument.from(ProductLine.OPTION,
                new RegisterInstrumentCommand("6", ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.CALL.ordinal(), 100, 10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000,
                                200_000, 50_000, 1_200_000))));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.SELL, 10, 2, 200_000, 100, 80,
                1_200_000)).isEqualTo(44);
        assertThat(OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                10, 10, 2, 100, 80, instrument.riskLimitBrackets().getFirst())).isEqualTo(24);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, -2, 10, 100, 80)).isEqualTo(32);
        assertThat(OptionContractMath.optionMarketValueUnits(instrument, -2, 10)).isEqualTo(-20);
    }

    /** 看跌期权的虚值方向与看涨相反，且标记权利金很高时应使用标记价口径的维持风险下限。 */
    @Test
    void putShortMarginUsesPutOtmDirectionAndMarkBasedMaintenanceFloor() {
        // 使用与看涨用例相同的档位参数，仅改变期权类型来验证看跌专属公式。
        CoreInstrument instrument = CoreInstrument.from(ProductLine.OPTION,
                new RegisterInstrumentCommand("7", ContractType.VANILLA_OPTION.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 2_000_000_000_000L,
                        OptionType.PUT.ordinal(), 100, 10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000,
                                200_000, 50_000, 1_200_000))));

        assertThat(CoreContractMath.openingMarginUnits(
                instrument, CoreOrderSide.SELL, 10, 2, 200_000, 100, 120,
                1_200_000)).isEqualTo(44);
        assertThat(CoreContractMath.maintenanceMarginUnits(instrument, -2, 200, 100, 120))
                .isEqualTo(424);
    }

    /** 杠杆倒数采用整数向上取整，不能被 3 倍杠杆的小数截断，也不能把极大杠杆算成 0 保证金率。 */
    @Test
    void initialMarginRateUsesExactAllocationFreeCeilingDivision() {
        assertThat(CoreContractMath.initialMarginRateFromLeverage(20_000_000)).isEqualTo(50_000);
        assertThat(CoreContractMath.initialMarginRateFromLeverage(3_000_000)).isEqualTo(333_334);
        assertThat(CoreContractMath.initialMarginRateFromLeverage(Long.MAX_VALUE)).isEqualTo(1);
    }

    /** 缩放要保留下限和上限顺序；中间乘积溢出后仍能得到封顶结果，下限高于上限时最终仍以封顶为准。 */
    @Test
    void openInterestScalingUsesLongFastPathAndPreservesOverflowSemantics() {
        assertThat(CoreArithmetic.scaledFloorCapped(
                20_000, 250_000, 1_000_000, 1_000, 10_000)).isEqualTo(5_000);
        assertThat(CoreArithmetic.scaledFloorCapped(
                1, 1, 1_000_000, 1_000, 10_000)).isEqualTo(1_000);
        assertThat(CoreArithmetic.scaledFloorCapped(
                Long.MAX_VALUE, 1_000_000, 1_000_000, 0, 5_000)).isEqualTo(5_000);
        assertThat(CoreArithmetic.scaledFloorCapped(
                1, 1, 1, 10_000, 5_000)).isEqualTo(5_000);
    }
}
