package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.FundsPosting;
import org.junit.jupiter.api.Test;
import static com.surprising.aeron.service.treasury.RuntimeFundsTestSupport.postings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金变化缓冲交接与守恒测试。反射核对指定原语数组交换、已有批次前缀合并和后续命令隔离；对象身份断言不代表全链路分配采样。
 */
class RuntimeFundsAccumulatorTest {

    /**
     * 写入多用户冻结转移后交给空目标，反射核对金额数组交换及源归空；源再写不影响目标，非法自交接和非空目标被拒绝。
     */
    @Test
    void handsOffBackingStorageAndKeepsSubsequentCommandsIsolated() throws Exception {
        // 需要交出本代资金流水的来源缓冲，转移后继续复用。
        var source = new RuntimeFundsAccumulator(1);
        // 接收空槽交接或已有批次前缀合并的目标缓冲。
        var target = new RuntimeFundsAccumulator(1);
        for (/* 构造六十四个用户冻结转移时的用户编号。 */ int user = 1; user <= 64; user++) {
            source.add(user % 3, FundsPosting.OwnerKind.USER, user, FundsPosting.Subledger.AVAILABLE, -user);
            source.add(user % 3, FundsPosting.OwnerKind.USER, user, FundsPosting.Subledger.LOCKED, user);
        }
        // 转移前或合并后物化保存的分录列表，用于核对后续写入隔离。
        var expected = postings(source);
        // 反射取得的原语金额数组字段。
        var storage = RuntimeFundsAccumulator.class.getDeclaredField("units");
        storage.setAccessible(true);
        // 来源在交接前持有的金额数组对象，用于身份断言。
        Object originalStorage = storage.get(source);
        source.transferTo(target);
        assertThat(storage.get(target)).isSameAs(originalStorage);
        assertThat(source.isEmpty()).isTrue();
        source.add(9, FundsPosting.OwnerKind.USER, 900, FundsPosting.Subledger.AVAILABLE, 17);
        assertThat(postings(target)).containsExactlyElementsOf(expected);
        assertThatCode(() -> target.requireConserved(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> target.transferToEmpty(source)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target.transferToEmpty(target)).isInstanceOf(IllegalArgumentException.class);
        source.clear();
        target.transferToEmpty(source);
        target.clear();
        assertThat(postings(source)).containsExactlyElementsOf(expected);
        assertThat(storage.get(source)).isSameAs(originalStorage);
    }

    /**
     * 目标已有前缀时合并来源，检查同键抵消为零、后续同键累计及源继续写不污染目标，最后检查非法目标拒绝且源保留。
     */
    @Test
    void transfersCompletedItemsIntoAnExistingBatchPrefixWithoutSharingStorage() {
        // 需要交出本代资金流水的来源缓冲，转移后继续复用。
        var source = new RuntimeFundsAccumulator(1);
        // 接收空槽交接或已有批次前缀合并的目标缓冲。
        var target = new RuntimeFundsAccumulator(1);
        target.add(7, FundsPosting.OwnerKind.USER, 11, FundsPosting.Subledger.AVAILABLE, -50);
        target.add(7, FundsPosting.OwnerKind.USER, 11, FundsPosting.Subledger.LOCKED, 50);
        source.add(7, FundsPosting.OwnerKind.USER, 11, FundsPosting.Subledger.AVAILABLE, 50);
        source.add(7, FundsPosting.OwnerKind.USER, 11, FundsPosting.Subledger.LOCKED, -50);
        source.add(9, FundsPosting.OwnerKind.USER, 12, FundsPosting.Subledger.AVAILABLE, -3);
        source.add(9, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FEE, 3);
        source.transferTo(target);
        assertThat(source.isEmpty()).isTrue();
        assertThat(postings(target)).hasSize(2).allSatisfy(/* 从目标物化结果逐项读取的资金分录，用于核对资产或金额。 */ posting ->
                assertThat(posting.assetId()).isEqualTo(9));
        target.requireConserved(false);

        source.add(9, FundsPosting.OwnerKind.USER, 12, FundsPosting.Subledger.AVAILABLE, -2);
        source.add(9, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FEE, 2);
        source.transferTo(target);
        // 转移前或合并后物化保存的分录列表，用于核对后续写入隔离。
        var expected = postings(target);
        assertThat(expected).anySatisfy(/* 从目标物化结果逐项读取的资金分录，用于核对资产或金额。 */ posting -> assertThat(posting.units()).isEqualTo(5));
        target.requireConserved(false);
        source.add(7, FundsPosting.OwnerKind.USER, 11, FundsPosting.Subledger.AVAILABLE, 1);
        assertThat(postings(target)).containsExactlyElementsOf(expected);
        assertThatThrownBy(() -> source.transferTo(source)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.transferTo(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(postings(source)).hasSize(1);
    }

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
