package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreCommandResultCodecTest {

    @Test
    void roundTripsBusinessResultFields() {
        CoreCommandResultView result = new CoreCommandResultView(List.of(),
                List.of(new CoreExecutionView(71, 70, 7, 8, 102, 1)));

        CoreCommandResultView restored = CoreCommandResultCodec.decode(CoreCommandResultCodec.encode(result));

        assertThat(restored).isEqualTo(result);
    }

    @Test
    void rejectsPreviousV4Result() {
        CoreCommandResultView result = new CoreCommandResultView(List.of(), List.of());
        byte[] encoded = CoreCommandResultCodec.encode(result);
        ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 4);

        assertThatThrownBy(() -> CoreCommandResultCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported Core protocol version: 4");
    }

    @Test
    void singleOrderEncodingMatchesListEncoding() {
        CoreOrderStateView order = new CoreOrderStateView(71, com.surprising.product.api.ProductLine.SPOT,
                7, "1", CoreOrderSide.BUY, 100, 3, 0, 3, false,
                "OPEN", 1);
        byte[] listEncoded = CoreCommandResultCodec.encode(List.of(order), List.of());
        byte[] singleEncoded = CoreCommandResultCodec.encodeSingleOrder(order);

        assertThat(singleEncoded).containsExactly(listEncoded);
        assertThat(CoreCommandResultCodec.decode(singleEncoded).orders()).containsExactly(order);
    }

    @Test
    void sourceListEncodingMatchesViewListEncoding() {
        CoreOrderStateView first = new CoreOrderStateView(71, com.surprising.product.api.ProductLine.SPOT,
                7, "1", CoreOrderSide.BUY, 100, 3, 0, 3, false,
                "OPEN", 1);
        CoreOrderStateView second = new CoreOrderStateView(72, com.surprising.product.api.ProductLine.SPOT,
                8, "1", CoreOrderSide.SELL, 101, 2, 1, 1, false,
                "OPEN", 2);

        byte[] viewEncoded = CoreCommandResultCodec.encode(List.of(first, second), List.of());
        byte[] sourceEncoded = CoreCommandResultCodec.encode(
                List.<CoreOrderStateSource>of(first, second), List.of());

        assertThat(sourceEncoded).containsExactly(viewEncoded);
        assertThat(CoreCommandResultCodec.decode(sourceEncoded).orders()).containsExactly(first, second);
    }

    @Test
    void reusableSingleOrderWriterRespectsPositionLimitAndChangesInEncodedLength() {
        CoreOrderStateView first = new CoreOrderStateView(71, com.surprising.product.api.ProductLine.SPOT,
                7, "1", CoreOrderSide.BUY, 100, 3, 0, 3, false, "OPEN", 1);
        CoreOrderStateView second = new CoreOrderStateView(72, com.surprising.product.api.ProductLine.SPOT,
                8, "123456789", CoreOrderSide.SELL, 101, 2, 2, 0, false, "FILLED", 2);
        byte[] storage = new byte[512];
        ByteBuffer writer = ByteBuffer.wrap(storage);
        for (var order : List.of(first, second, first)) {
            java.util.Arrays.fill(storage, (byte) 99);
            byte[] expected = CoreCommandResultCodec.encode(List.of(order), List.of());
            writer.clear().position(7).limit(7 + expected.length);
            assertThat(CoreCommandResultCodec.encodeSingleOrderInto(order, writer)).isEqualTo(expected.length);
            assertThat(writer.position()).isEqualTo(7 + expected.length);
            assertThat(java.util.Arrays.copyOfRange(storage, 7, writer.position())).containsExactly(expected);
            assertThat(storage[6]).isEqualTo((byte) 99);
            assertThat(storage[writer.position()]).isEqualTo((byte) 99);
            java.util.Arrays.fill(storage, (byte) 99);
            assertThat(CoreCommandResultCodec.encodeSingleOrderInto(order, storage, 7)).isEqualTo(expected.length);
            assertThat(java.util.Arrays.copyOfRange(storage, 7, 7 + expected.length)).containsExactly(expected);
        }
        writer.clear().limit(1);
        assertThatThrownBy(() -> CoreCommandResultCodec.encodeSingleOrderInto(first, writer))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(writer.position()).isZero();
        assertThatThrownBy(() -> CoreCommandResultCodec.encodeSingleOrderInto(first, writer.asReadOnlyBuffer()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
