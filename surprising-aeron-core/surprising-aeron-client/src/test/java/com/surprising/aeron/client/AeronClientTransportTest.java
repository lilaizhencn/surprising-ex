package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AeronClientTransportTest {

    @Test
    void ownsOneMediaDriverAcrossSequentialClusterSessions() {
        AeronClientTransport transport = AeronClientTransport.launch();
        try {
            assertThat(transport.mediaDriver()).isSameAs(transport.mediaDriver());
        } finally {
            transport.close();
        }
        transport.close();

        assertThatIllegalStateException().isThrownBy(() -> transport.connect(
                ProductLine.SPOT,
                List.of("localhost", "localhost", "localhost"),
                "localhost",
                Duration.ofSeconds(1)));
    }
}
