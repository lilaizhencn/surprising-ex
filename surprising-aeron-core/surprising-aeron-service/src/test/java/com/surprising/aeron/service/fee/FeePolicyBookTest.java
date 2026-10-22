package com.surprising.aeron.service.fee;

import com.surprising.aeron.model.CoreFeePolicyState;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 费用政策不可变版本、优先顺序、用户标的匹配、生效区间及同修订幂等冲突测试。
 */
class FeePolicyBookTest {
    /**
     * 用用户7的三条同优先级政策核对时间和标的选择，再加入更高优先级政策，检查原版本不变、相同内容复用、同修订改内容冲突及旧修订拒绝；从映射构建时键与政策ID不一致也拒绝。
     */
    @Test void immutableVersionsKeepPriorityExpiryAndRevisionSemantics() {
        // 用户7全部标的的基础政策，生效100且不失效
        var userWide = policy(1, 1, "", 3, 100, 0, 10);
        // 仅标的1的政策，生效100、失效300
        var symbol = policy(2, 1, "1", 3, 100, 300, 20);
        // 同优先级较晚生效的标的1政策，150生效、200失效
        var latest = policy(3, 1, "1", 3, 150, 200, 30);
        // 最初三条政策构成的不可变版本
        var original = FeePolicyBook.from(Map.of(1L, userWide, 2L, symbol, 3L, latest));
        assertThat(original.select(7, "1", 180)).isSameAs(latest);
        assertThat(original.select(7, "1", 200)).isSameAs(symbol);
        assertThat(original.select(7, "1", 300)).isSameAs(userWide);
        assertThat(original.select(8, "1", 180)).isNull();
        assertThat(original.select(7, "2", 180)).isSameAs(userWide);
        // 优先级数值0的用户全标的政策，覆盖原优先级3
        var higherPriority = policy(4, 1, "", 0, 100, 0, 40);
        // 加入更高优先级政策后的新版本，原版本仍保留
        var changed = original.upsert(higherPriority);
        assertThat(changed.select(7, "1", 180)).isSameAs(higherPriority);
        assertThat(original.select(7, "1", 180)).isSameAs(latest);
        assertThat(changed.upsert(higherPriority)).isSameAs(changed);
        assertThatThrownBy(() -> changed.upsert(policy(4, 1, "", 0, 100, 0, 41)))
                .hasMessageContaining("different data");
        // 政策4提高修订并改费率后的版本
        var revised = changed.upsert(policy(4, 2, "", 0, 100, 0, 42));
        assertThat(revised.select(7, "1", 180).makerFeeRatePpm()).isEqualTo(42);
        assertThatThrownBy(() -> revised.upsert(higherPriority)).hasMessageContaining("version must increase");
        assertThat(changed.snapshot()).hasSize(4);
        assertThatThrownBy(() -> FeePolicyBook.from(Map.of(9L, latest)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 为固定用户7建立启用政策，maker和taker使用相同费率，其他生效与优先参数由本例指定。
     * @param id 政策身份
     * @param revision 政策修订号
     * @param symbol 政策标的范围，空文本表示全部标的
     * @param priority 政策优先级数值，由模型比较规则排序
     * @param start 政策生效起点业务时间
     * @param expiry 政策失效边界，零表示不设到期
     * @param fee maker及taker共同使用的百万分比费率
     */
    private static CoreFeePolicyState policy(long id, long revision, String symbol, int priority,
            long start, long expiry, long fee) {
        return new CoreFeePolicyState(id, revision, 7, symbol, fee, fee, priority, true, start, expiry);
    }
}
