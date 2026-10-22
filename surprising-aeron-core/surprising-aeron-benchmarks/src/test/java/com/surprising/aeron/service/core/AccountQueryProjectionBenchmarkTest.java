package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreStateQueryCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccountQueryProjectionBenchmarkTest {
    @Test void allThreeProjectionPathsEncodeTheSameOwnedUserAndOrder() {
        var benchmark = new AccountQueryProjectionBenchmark();
        benchmark.setup();
        try {
            benchmark.queryType = "USER";
            var user = CoreStateQueryCodec.decodeUserState(benchmark.projectAndEncode());
            assertThat(user.userId()).isEqualTo(7);
            assertThat(user.balances()).hasSize(1);
            assertThat(user.reservations()).hasSize(1);
            assertThat(user.positions()).hasSize(1);
            assertThat(user.leverages()).hasSize(1);
            benchmark.queryType = "ORDER";
            byte[] order = benchmark.projectAndEncode();
            assertThat(CoreStateQueryCodec.decodeOrderState(order).orderId()).isEqualTo(71);
            benchmark.queryType = "CLIENT";
            assertThat(benchmark.projectAndEncode()).isEqualTo(order);
        } finally { benchmark.close(); }
    }
}
