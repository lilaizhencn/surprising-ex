package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.TradeSettlement;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金变化缓冲守恒测试。
 */
class FundsTotalTest {

    /**
     * 直接对原语缓冲核对两个资产守恒，加入不平衡保险分录后仅非外部模式拒绝；用例不依赖先物化分录值来触发检查。
     */
    @Test
    void validatesConservationWithoutMaterializingFundsDelta() {
        // 含两个资产平衡分录的原语资金缓冲。
        FundsTotal accumulator = new FundsTotal();
        accumulator.add(7, TradeSettlement.Posting.OwnerKind.USER, 11,
                TradeSettlement.Posting.Subledger.AVAILABLE, -100);
        accumulator.add(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0,
                TradeSettlement.Posting.Subledger.FEE, 100);
        accumulator.add(9, TradeSettlement.Posting.OwnerKind.USER, 12,
                TradeSettlement.Posting.Subledger.LOCKED, -40);
        accumulator.add(9, TradeSettlement.Posting.OwnerKind.USER, 13,
                TradeSettlement.Posting.Subledger.AVAILABLE, 40);

        assertThatCode(() -> accumulator.requireConserved(false)).doesNotThrowAnyException();

        accumulator.add(9, TradeSettlement.Posting.OwnerKind.TREASURY, 0,
                TradeSettlement.Posting.Subledger.INSURANCE, 1);
        assertThatThrownBy(() -> accumulator.requireConserved(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset 9");
        assertThatCode(() -> accumulator.requireConserved(true)).doesNotThrowAnyException();
    }
}
