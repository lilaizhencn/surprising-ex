package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.aeron.service.state.snapshot.*;
import com.surprising.aeron.service.state.snapshot.CoreFeePolicySnapshotCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证费率策略快照按稳定策略标识排序并可完整恢复两条不同作用范围、返佣/费率及有效期字段的策略；不执行业务费率选择或手续费结算。
 */
class CoreFeePolicySnapshotCodecTest {

    /**
     * 编码相同两条策略的两种 Map 构造写法，要求字节相同，解码值等于原表且键按七、九排列。Map.of 迭代顺序本身未受此测试控制，生产排序规则由编解码器实现。
     */
    @Test
    void roundTripsPoliciesInStableIdOrder() {
        // 策略七和九的完整测试数据，包含负 maker 返佣与正 taker 费率。
        Map<Long, CoreFeePolicyState> policies = Map.of(
                9L, new CoreFeePolicyState(9, 3, 1001, "", -10, 25, 4, true, 1_000, 0),
                7L, new CoreFeePolicyState(7, 2, 1001, "1", -25, 75, 0, true, 900, 2_000));

        // 第一次编码的费率策略字节。
        byte[] first = CoreFeePolicySnapshotCodec.encode(policies);
        // 以另一种 Map 构造写法编码的字节，要求与第一次一致。
        byte[] second = CoreFeePolicySnapshotCodec.encode(Map.of(7L, policies.get(7L), 9L, policies.get(9L)));

        assertThat(second).containsExactly(first);
        assertThat(CoreFeePolicySnapshotCodec.decode(first)).isEqualTo(policies);
        assertThat(CoreFeePolicySnapshotCodec.decode(first).keySet()).containsExactly(7L, 9L);
    }
}
