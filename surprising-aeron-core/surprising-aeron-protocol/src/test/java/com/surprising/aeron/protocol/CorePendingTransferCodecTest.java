package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CorePendingTransferCodecTest {
    private final TransferFundsCommand transfer = new TransferFundsCommand(91, ProductLine.SPOT, ProductLine.SPOT,
            "SPOT", "SPOT", "USDT", 37, "跨用户", "allocation", 7, 8);

    @Test void responseContainsOnlyCountLengthAndOriginalCommand() {
        byte[] command = TradingCommandCodec.encodeTransferFunds(transfer);
        byte[] response = CorePendingTransferCodec.encode(List.of(transfer));
        assertThat(response).hasSize(8 + command.length);
        var frame = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(frame.getInt()).isOne();
        assertThat(frame.getInt()).isEqualTo(command.length);
        assertThat(Arrays.copyOfRange(response, 8, response.length)).containsExactly(command);
        assertThat(CorePendingTransferCodec.decode(response)).containsExactly(transfer);
        assertThat(CorePendingTransferCodec.decode(CorePendingTransferCodec.encode(List.of()))).isEmpty();
    }

    @Test void rejectsTruncationInvalidLengthsCountsAndTrailingBytes() {
        byte[] response = CorePendingTransferCodec.encode(List.of(transfer));
        for (int length : new int[]{0, 3, 4, 7, response.length - 1}) {
            byte[] truncated = Arrays.copyOf(response, length);
            assertThatThrownBy(() -> CorePendingTransferCodec.decode(truncated)).isInstanceOf(ProtocolException.class);
        }
        for (int count : new int[]{-1, CorePendingTransferCodec.MAX_RESULTS + 1}) {
            byte[] invalid = response.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(count);
            assertThatThrownBy(() -> CorePendingTransferCodec.decode(invalid)).isInstanceOf(ProtocolException.class);
        }
        for (int length : new int[]{0, -1, response.length}) {
            byte[] invalid = response.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(4, length);
            assertThatThrownBy(() -> CorePendingTransferCodec.decode(invalid)).isInstanceOf(ProtocolException.class);
        }
        assertThatThrownBy(() -> CorePendingTransferCodec.decode(Arrays.copyOf(response, response.length + 1)))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("trailing");
    }
}
