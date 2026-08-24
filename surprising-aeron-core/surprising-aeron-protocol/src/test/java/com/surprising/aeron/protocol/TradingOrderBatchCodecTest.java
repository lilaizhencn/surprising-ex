package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingOrderBatchCodecTest {

    @Test
    void roundTripsVersionedLengthPrefixedCanonicalBatchCommandsAndResults() {
        PlaceOrderCommand place = place(701, "place-701");
        CancelOrderCommand cancel = new CancelOrderCommand(701);
        AmendOrderCommand amend = new AmendOrderCommand(701, 702, "amend-702", 1_100L,
                2L, CoreTimeInForce.GTC, false);

        PlaceOrderBatchCommand places = new PlaceOrderBatchCommand(List.of(place));
        CancelOrderBatchCommand cancels = new CancelOrderBatchCommand(List.of(cancel));
        AmendOrderBatchCommand amends = new AmendOrderBatchCommand(List.of(amend));

        assertThat(TradingOrderBatchCodec.decodePlaceOrderBatch(
                TradingOrderBatchCodec.encodePlaceOrderBatch(places))).isEqualTo(places);
        assertThat(TradingOrderBatchCodec.decodeCancelOrderBatch(
                TradingOrderBatchCodec.encodeCancelOrderBatch(cancels))).isEqualTo(cancels);
        assertThat(TradingOrderBatchCodec.decodeAmendOrderBatch(
                TradingOrderBatchCodec.encodeAmendOrderBatch(amends))).isEqualTo(amends);

        CoreOrderBatchResult result = new CoreOrderBatchResult(List.of(
                new CoreOrderBatchResult.Item(0, 701, 0, 0, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, null, List.of()),
                new CoreOrderBatchResult.Item(1, 701, 0, 0, ResponseStatus.REJECTED,
                        CoreResultCode.DUPLICATE_ORDER_ID, null, List.of())));
        assertThat(TradingOrderBatchCodec.decodeResult(
                TradingOrderBatchCodec.encodeResult(result))).isEqualTo(result);
    }

    @Test
    void rejectsEmptyAndOverLimitBatchCommands() {
        assertThatThrownBy(() -> new PlaceOrderBatchCommand(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancelOrderBatchCommand(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmendOrderBatchCommand(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PlaceOrderBatchCommand(repeatedPlaces(21)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancelOrderBatchCommand(repeatedCancels(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmendOrderBatchCommand(repeatedAmends(21)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedTruncatedTrailingVersionAndOversizedFrames() {
        byte[] valid = TradingOrderBatchCodec.encodePlaceOrderBatch(
                new PlaceOrderBatchCommand(List.of(place(702, "place-702"))));

        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatch(
                Arrays.copyOf(valid, valid.length - 1))).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatch(
                Arrays.copyOf(valid, valid.length + 1))).isInstanceOf(ProtocolException.class);

        byte[] unsupportedVersion = valid.clone();
        ByteBuffer.wrap(unsupportedVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatch(unsupportedVersion))
                .isInstanceOf(ProtocolException.class);

        byte[] negativeLength = valid.clone();
        ByteBuffer.wrap(negativeLength).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatch(negativeLength))
                .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatch(
                new byte[TradingOrderBatchCodec.MAX_BATCH_PAYLOAD_BYTES + 1]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodeResult(
                new byte[TradingOrderBatchCodec.MAX_BATCH_RESPONSE_BYTES + 1]))
                .isInstanceOf(ProtocolException.class);

        List<CoreExecutionView> oversizedExecutions = java.util.stream.IntStream.range(0, 100_000)
                .mapToObj(index -> new CoreExecutionView(1, 2, 3, 4, 5, 6)).toList();
        CoreOrderBatchResult oversizedResult = new CoreOrderBatchResult(List.of(
                new CoreOrderBatchResult.Item(0, 702, 0, 0, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, null, oversizedExecutions)));
        assertThatThrownBy(() -> TradingOrderBatchCodec.encodeResult(oversizedResult))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateAndMissingResultIndexes() {
        CoreOrderBatchResult result = new CoreOrderBatchResult(List.of(
                new CoreOrderBatchResult.Item(0, 701, 0, 0, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, null, List.of()),
                new CoreOrderBatchResult.Item(1, 702, 0, 0, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, null, List.of())));
        byte[] encoded = TradingOrderBatchCodec.encodeResult(result);

        byte[] duplicate = encoded.clone();
        ByteBuffer duplicateBuffer = ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN);
        int firstFrameLength = duplicateBuffer.getInt(Integer.BYTES * 2);
        int secondFrameIndex = Integer.BYTES * 3 + firstFrameLength;
        duplicateBuffer.putInt(secondFrameIndex, 0);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodeResult(duplicate))
                .isInstanceOf(ProtocolException.class);

        byte[] missing = encoded.clone();
        ByteBuffer missingBuffer = ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN);
        missingBuffer.putInt(Integer.BYTES, 3);
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodeResult(missing))
                .isInstanceOf(ProtocolException.class);
    }

    private static PlaceOrderCommand place(long orderId, String clientOrderId) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 1_000, 1_001, 1_100, 999, 1,
                false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.SPOT_ASSET, "USDT", 1_000, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                false, clientOrderId, 0, 0);
    }

    private static List<PlaceOrderCommand> repeatedPlaces(int count) {
        List<PlaceOrderCommand> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(place(800 + index, "place-" + index));
        return result;
    }

    private static List<CancelOrderCommand> repeatedCancels(int count) {
        List<CancelOrderCommand> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(new CancelOrderCommand(900 + index));
        return result;
    }

    private static List<AmendOrderCommand> repeatedAmends(int count) {
        List<AmendOrderCommand> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new AmendOrderCommand(1_000 + index, 2_000 + index,
                    "amend-" + index, 1_000L, null, null, null));
        }
        return result;
    }
}
