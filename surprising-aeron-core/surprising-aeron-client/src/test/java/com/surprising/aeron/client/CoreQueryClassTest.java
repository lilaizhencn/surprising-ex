package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreQueryClassTest {

    @Test
    void reservesCapacityOnlyForCommandResultPreflightAndLifecycleAdminControls() {
        assertThat(CoreQueryClass.classify(CoreMessageType.COMMAND_RESULT_QUERY))
                .isEqualTo(CoreQueryClass.RESERVED_CONTROL);
        assertThat(CoreQueryClass.classify(CoreMessageType.ORDER_PREFLIGHT_QUERY))
                .isEqualTo(CoreQueryClass.RESERVED_CONTROL);
        assertThat(CoreQueryClass.classify(CoreMessageType.LIQUIDATION_WORK_QUERY))
                .isEqualTo(CoreQueryClass.RESERVED_CONTROL);
        assertThat(CoreQueryClass.classify(CoreMessageType.EXPORT_STATUS_QUERY))
                .isEqualTo(CoreQueryClass.RESERVED_CONTROL);
        assertThat(CoreQueryClass.classify(CoreMessageType.USER_STATE_QUERY))
                .isEqualTo(CoreQueryClass.ORDINARY_READ);
        assertThat(CoreQueryClass.classify(CoreMessageType.ORDER_STATE_QUERY))
                .isEqualTo(CoreQueryClass.ORDINARY_READ);
        assertThat(CoreQueryClass.classify(CoreMessageType.USER_OPEN_ORDERS_QUERY))
                .isEqualTo(CoreQueryClass.ORDINARY_READ);
    }

    @Test
    void rejectsOrdinaryReadsBeforeTheyReachTheReservedMailbox() {
        try (AeronClientPool pool = new AeronClientPool("query-class", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1),
                "query-class", "epoch", AeronClientCapacity.defaults(),
                () -> { throw new AssertionError("agents are paused"); }, false)) {
            assertThatIllegalArgumentException().isThrownBy(() -> pool.query(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), 1, new byte[0])).withMessageContaining("ordinary Core reads");
            assertThat(pool.controlQueryAsync(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), 1,
                    new byte[0])).isNotCompleted();
        }
    }
}
