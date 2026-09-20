package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreCommandResultCodecTest {

    @Test
    void roundTripsMatcherPrefixIntegrityFields() {
        UUID commandId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        CoreCommandResultView result = new CoreCommandResultView(41, commandId, 71, 83,
                0x1020_3040_5060_7080L, 0x1121_3141_5161_7181L, List.of(),
                List.of(new CoreExecutionView(71, 70, 7, 8, 102, 1)));

        CoreCommandResultView restored = CoreCommandResultCodec.decode(CoreCommandResultCodec.encode(result));

        assertThat(restored).isEqualTo(result);
        assertThat(restored.coreSequence()).isEqualTo(41);
        assertThat(restored.commandId()).isEqualTo(commandId);
        assertThat(restored.orderId()).isEqualTo(71);
        assertThat(restored.matcherSequence()).isEqualTo(83);
        assertThat(restored.matcherPrefixBefore()).isEqualTo(0x1020_3040_5060_7080L);
        assertThat(restored.matcherPrefixAfter()).isEqualTo(0x1121_3141_5161_7181L);
    }

    @Test
    void rejectsV2Result() {
        CoreCommandResultView result = new CoreCommandResultView(41, UUID.randomUUID(), 71, 83,
                17, 19, List.of(), List.of());
        byte[] encoded = CoreCommandResultCodec.encode(result);
        ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2);

        assertThatThrownBy(() -> CoreCommandResultCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported Core protocol version: 2");
    }

    @Test
    void singleOrderEncodingMatchesListEncoding() {
        UUID commandId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        CoreOrderStateView order = new CoreOrderStateView(71, com.surprising.product.api.ProductLine.SPOT,
                7, "BTC-USDT", CoreOrderSide.BUY, 100, 3, 0, 3, false,
                "OPEN", 1);
        byte[] listEncoded = CoreCommandResultCodec.encode(41, commandId, 71, 83, 17, 19,
                List.of(order), List.of());
        byte[] singleEncoded = CoreCommandResultCodec.encodeSingleOrder(41, commandId, 71, 83,
                17, 19, order);

        assertThat(singleEncoded).containsExactly(listEncoded);
        assertThat(CoreCommandResultCodec.decode(singleEncoded).orders()).containsExactly(order);
    }

    @Test
    void sourceListEncodingMatchesViewListEncoding() {
        UUID commandId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        CoreOrderStateView first = new CoreOrderStateView(71, com.surprising.product.api.ProductLine.SPOT,
                7, "BTC-USDT", CoreOrderSide.BUY, 100, 3, 0, 3, false,
                "OPEN", 1);
        CoreOrderStateView second = new CoreOrderStateView(72, com.surprising.product.api.ProductLine.SPOT,
                8, "BTC-USDT", CoreOrderSide.SELL, 101, 2, 1, 1, false,
                "OPEN", 2);

        byte[] viewEncoded = CoreCommandResultCodec.encode(41, commandId, 71, 83, 17, 19,
                List.of(first, second), List.of());
        byte[] sourceEncoded = CoreCommandResultCodec.encode(41, commandId, 71, 83, 17, 19,
                List.<CoreOrderStateSource>of(first, second), List.of());

        assertThat(sourceEncoded).containsExactly(viewEncoded);
        assertThat(CoreCommandResultCodec.decode(sourceEncoded).orders()).containsExactly(first, second);
    }
}
