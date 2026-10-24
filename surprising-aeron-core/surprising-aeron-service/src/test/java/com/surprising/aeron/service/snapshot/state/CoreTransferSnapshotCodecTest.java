package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreTransferSnapshotCodecTest {
    private final TransferFundsCommand transfer = new TransferFundsCommand(91, ProductLine.SPOT, ProductLine.SPOT,
            "SPOT", "SPOT", "USDT", 37, "跨用户", "allocation", 7, 8);

    @Test void savesEveryCommandFieldWithoutAnExtraOwnerUser() {
        byte[] encoded = CoreTransferSnapshotCodec.encode(Map.of(91L, transfer));
        assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt()).isEqualTo(3);
        var items = new SnapshotFields.Reader(Arrays.copyOfRange(encoded, 4, encoded.length))
                .list(1, SnapshotFields.Reader::new);
        assertThat(items).singleElement().satisfies(item -> {
            assertThatThrownBy(() -> item.number(1)).isInstanceOf(ProtocolException.class).hasMessageContaining("missing");
            assertThat(item.number(11)).isEqualTo(7);
            assertThat(item.number(12)).isEqualTo(8);
        });
        assertThat(CoreTransferSnapshotCodec.decode(encoded)).containsExactlyEntriesOf(Map.of(91L, transfer));
        assertThat(CoreTransferSnapshotCodec.decode(CoreTransferSnapshotCodec.encode(Map.of()))).isEmpty();
    }

    @Test void rejectsOldVersionAndTruncatedCurrentRecords() {
        byte[] encoded = CoreTransferSnapshotCodec.encode(Map.of(91L, transfer));
        byte[] old = encoded.clone();
        ByteBuffer.wrap(old).order(ByteOrder.LITTLE_ENDIAN).putInt(2);
        assertThatThrownBy(() -> CoreTransferSnapshotCodec.decode(old)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported");
        for (int length : new int[]{0, 3, 7, encoded.length - 1}) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> CoreTransferSnapshotCodec.decode(truncated)).isInstanceOf(ProtocolException.class);
        }
    }
}
