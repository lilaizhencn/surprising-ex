package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.FundsPosting;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金变化缓冲守恒测试。
 */
class RuntimeFundsAccumulatorTest {

    /**
     * 直接对原语缓冲核对两个资产守恒，加入不平衡保险分录后仅非外部模式拒绝；用例不依赖先物化分录值来触发检查。
     */
    @Test
    void validatesConservationWithoutMaterializingFundsDelta() {
        // 含两个资产平衡分录的原语资金缓冲。
        RuntimeFundsAccumulator accumulator = new RuntimeFundsAccumulator();
        accumulator.add(7, FundsPosting.OwnerKind.USER, 11,
                FundsPosting.Subledger.AVAILABLE, -100);
        accumulator.add(7, FundsPosting.OwnerKind.TREASURY, 0,
                FundsPosting.Subledger.FEE, 100);
        accumulator.add(9, FundsPosting.OwnerKind.USER, 12,
                FundsPosting.Subledger.LOCKED, -40);
        accumulator.add(9, FundsPosting.OwnerKind.USER, 13,
                FundsPosting.Subledger.AVAILABLE, 40);

        assertThatCode(() -> accumulator.requireConserved(false)).doesNotThrowAnyException();

        accumulator.add(9, FundsPosting.OwnerKind.TREASURY, 0,
                FundsPosting.Subledger.INSURANCE, 1);
        assertThatThrownBy(() -> accumulator.requireConserved(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset 9");
        assertThatCode(() -> accumulator.requireConserved(true)).doesNotThrowAnyException();
    }
}
