package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AeronClientPoolTest {

    @Test
    void rejectsInvalidConfigurationBeforeOpeningConnections() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost"), "localhost", Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ZERO, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AeronClientPool("order", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1), 0));
    }

    @Test
    void rejectsMessageTypesWithTheWrongWireKindWithoutConnecting() {
        try (AeronClientPool pool = pool()) {
            assertThatIllegalArgumentException().isThrownBy(() -> pool.command(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), 1, new byte[0]));
            assertThatIllegalArgumentException().isThrownBy(() -> pool.query(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), 1, new byte[0]));
        }
    }

    private static AeronClientPool pool() {
        return new AeronClientPool("test", ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(1), 1);
    }
}
