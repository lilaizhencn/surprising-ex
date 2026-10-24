package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreCancelAllAfterCodecTest {
    @Test
    void roundTripsCommandQueryAndViews() {
        List<CoreCancelAllAfterCommand> commands = List.of(
                CoreCancelAllAfterCommand.set("1", 1000, 2001),
                CoreCancelAllAfterCommand.claim("1", 4, 2001),
                CoreCancelAllAfterCommand.complete("1", 4, 2001),
                CoreCancelAllAfterCommand.retry("1", 4, 2001));
        CoreCancelAllAfterView view = new CoreCancelAllAfterView(101, "1", 1_000,
                CoreCancelAllAfterStatus.TRIGGERING, 2_000, 2_001, 5);

        assertThat(commands).allSatisfy(command -> {
            byte[] encoded = CoreCancelAllAfterCodec.encodeCommand(command);
            assertThat(encoded).hasSize(Integer.BYTES * 3 + Long.BYTES * 2 + 1);
            assertThat(CoreCancelAllAfterCodec.decodeCommand(encoded)).isEqualTo(command);
        });
        assertThat(CoreCancelAllAfterCodec.decodeQuery(
                CoreCancelAllAfterCodec.encodeQuery(101, "1", 2_000, 100)))
                .isEqualTo(new CoreCancelAllAfterCodec.Query(101, "1", 2_000, 100));
        assertThat(CoreCancelAllAfterCodec.decodeList(CoreCancelAllAfterCodec.encodeList(List.of(view))))
                .containsExactly(view);
    }
}
