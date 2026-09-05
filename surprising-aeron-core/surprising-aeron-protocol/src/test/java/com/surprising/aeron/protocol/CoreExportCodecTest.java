package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreExportCodecTest {

    @Test
    void encodesUnsignedEventWithoutIntegrityTrailer() {
        CoreExportEvent event = event(ProductLine.SPOT, 1, UUID.randomUUID());

        byte[] encoded = CoreExportCodec.encodeEvent(event);

        assertThat(CoreExportCodec.encodedEventLength(event)).isEqualTo(encoded.length);
        assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt())
                .isEqualTo(0xC0E7_000B);
    }

    @Test
    void rejectsLegacySignedEventVersion() {
        byte[] legacy = CoreExportCodec.encodeEvent(event(ProductLine.SPOT, 1, UUID.randomUUID()));
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC0E7_0009);

        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(legacy))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("unsupported export event version");
    }

    @Test
    void roundTripsEventBatchAckAndStatus() {
        UUID commandId = UUID.randomUUID();
        CoreExecutionView execution = new CoreExecutionView(71, 72, 17, 18, 60_000, 1);
        CoreFundingPaymentView funding = new CoreFundingPaymentView(8, 17, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, "USDT", 2, 120_000, 100, -12);
        var terminalIds = new CoreExportEvent.TerminalIds(List.of(71L), List.of(3L), List.of(9L));
        var fundingProgress = new CoreFundingProgressView(8, false, 18, 2);
        var settlementProgress = new CoreSettlementProgressView(9, false, false, 72, 0, 1, 0);
        var postings = List.of(new CoreFundsPostingView("USDT", CoreFundsPostingView.OwnerKind.USER, 17,
                CoreFundsPostingView.Subledger.AVAILABLE, 12));
        CoreMessage command = command(ProductLine.SPOT, 7, commandId, 17, new byte[]{1, 2, 3});
        CoreExportEvent event = new CoreExportEvent(7, 7, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                17, command.payloadUnsafe(), List.of(execution), List.of(funding),
                CoreRoute.DEFAULT.version(), 7, 23, postings, CommandFingerprint.of(command), terminalIds,
                6, 7, 20, 21, fundingProgress, settlementProgress);
        CoreMessage message = new CoreMessage(command.header().exportEvent(7), CoreExportCodec.encodeEvent(event));

        assertThat(CoreExportCodec.encodedEventLength(event)).isEqualTo(message.payloadLength());
        CoreExportEvent restored = CoreExportCodec.decodeEvent(message.payload());
        List<CoreMessage> batch = CoreExportCodec.decodeBatch(CoreExportCodec.encodeBatch(List.of(message)));
        CoreExportStatus status = new CoreExportStatus(6, 8, 1, 256, 1_000_000, 64L * 1024 * 1024);
        CoreExportBatch batchWithStatus = CoreExportCodec.decodeBatchResponse(
                CoreExportCodec.encodeBatchWithStatus(status, List.of(message)));

        assertThat(restored.exportSequence()).isEqualTo(7);
        assertThat(restored.commandId()).isEqualTo(commandId);
        assertThat(restored.commandType()).isEqualTo(CoreMessageType.ADJUST_BALANCE);
        assertThat(restored.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(restored.resultCode()).isEqualTo(CoreResultCode.NONE);
        assertThat(restored.userId()).isEqualTo(17);
        assertThat(restored.commandPayload()).containsExactly(1, 2, 3);
        assertThat(restored.executions()).containsExactly(execution);
        assertThat(restored.fundingPayments()).containsExactly(funding);
        assertThat(restored.routeVersion()).isEqualTo(CoreRoute.DEFAULT.version());
        assertThat(restored.committedCoreSequence()).isEqualTo(7);
        assertThat(restored.clusterPosition()).isEqualTo(23);
        assertThat(restored.fundsPostings()).isEqualTo(postings);
        assertThat(restored.commandFingerprint()).isEqualTo(event.commandFingerprint());
        assertThat(restored.terminalIds()).isEqualTo(terminalIds);
        assertThat(restored.previousCoreSequence()).isEqualTo(6);
        assertThat(restored.coreSequence()).isEqualTo(7);
        assertThat(restored.previousProjectionSequence()).isEqualTo(20);
        assertThat(restored.projectionSequence()).isEqualTo(21);
        assertThat(restored.fundingProgress()).isEqualTo(fundingProgress);
        assertThat(restored.settlementProgress()).isEqualTo(settlementProgress);
        assertThat(CoreExportCodec.encodeEvent(restored)).containsExactly(message.payloadUnsafe());
        assertThat(batch).containsExactly(message);
        assertThat(batch.getFirst().header().route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(batchWithStatus.acknowledgedSequence()).isEqualTo(6);
        assertThat(batchWithStatus.events()).containsExactly(message);
        assertThat(CoreExportCodec.decodeAck(CoreExportCodec.encodeAck(new AckExportCommand(7))))
                .isEqualTo(new AckExportCommand(7));
        assertThat(CoreExportCodec.decodeStatus(CoreExportCodec.encodeStatus(status))).isEqualTo(status);
        assertThat(batchWithStatus.status()).isEqualTo(status);
    }

    @Test
    void rejectsTruncatedOrTrailingPayloads() {
        byte[] encodedEvent = CoreExportCodec.encodeEvent(event(ProductLine.SPOT, 1, UUID.randomUUID()));
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(Arrays.copyOf(encodedEvent, encodedEvent.length - 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(Arrays.copyOf(encodedEvent, encodedEvent.length + 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatch(new byte[]{1, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeAck(new byte[7]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchQuery(new byte[]{0, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void batchResponseCarriesCompletePostQueryStatus() {
        CoreExportStatus expected = new CoreExportStatus(12, 17, 4, 1_024,
                1_000_000, 64L * 1024 * 1024);

        CoreExportBatch actual = CoreExportCodec.decodeBatchResponse(
                CoreExportCodec.encodeBatchWithStatus(expected, List.of()));

        assertThat(actual.status()).isEqualTo(expected);
        assertThat(actual.events()).isEmpty();
    }

    @Test
    void rejectsMalformedStatusBearingBatchResponses() {
        CoreExportStatus status = new CoreExportStatus(0, 1, 0, 0, 1_000_000, 64L * 1024 * 1024);
        byte[] encoded = CoreExportCodec.encodeBatchWithStatus(status, List.of());

        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(
                Arrays.copyOf(encoded, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(
                Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(ProtocolException.class);

        byte[] invalidStatus = encoded.clone();
        ByteBuffer.wrap(invalidStatus).order(ByteOrder.LITTLE_ENDIAN).putInt(20, -1);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(invalidStatus))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void enforcesCompleteStatusBearingResponseMaximumAtBoundaryAndPlusOne() {
        CoreExportStatus status = new CoreExportStatus(0, 2, 1, 64, 1_000_000, 64L * 1024 * 1024);
        int maximumInnerBatchLength = CoreExportCodec.MAX_BATCH_ENCODED_LENGTH
                - CoreExportCodec.BATCH_STATUS_FIXED_LENGTH;
        CoreMessage maximumMessage = messageForBatchLength(maximumInnerBatchLength);
        byte[] exact = CoreExportCodec.encodeBatchWithStatus(status, List.of(maximumMessage));

        assertThat(exact).hasSize(CoreExportCodec.MAX_BATCH_ENCODED_LENGTH);
        assertThat(CoreExportCodec.decodeBatchResponse(exact).events()).containsExactly(maximumMessage);

        byte[] oversizedInnerBatch = CoreExportCodec.encodeBatch(
                List.of(messageForBatchLength(maximumInnerBatchLength + 1)));
        byte[] oversized = withStatusHeader(status, oversizedInnerBatch);
        assertThat(oversized).hasSize(CoreExportCodec.MAX_BATCH_ENCODED_LENGTH + 1);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(oversized))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void completeExportResponseFitsTheClusterPublicationMaximum() {
        int encodedCoreMessageLength = CoreProtocol.HEADER_LENGTH
                + CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH
                + CoreExportCodec.MAX_BATCH_ENCODED_LENGTH;

        assertThat(encodedCoreMessageLength).isLessThanOrEqualTo(CoreProtocol.CLUSTER_MAX_MESSAGE_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void roundTripsCurrentContractForEveryProductLine(ProductLine productLine) {
        UUID commandId = UUID.randomUUID();
        CoreMessage command = command(productLine, 7, commandId, 17, new byte[]{7});
        CoreExportEvent event = event(productLine, 7, commandId);
        CoreMessage envelope = new CoreMessage(command.header().exportEvent(7), CoreExportCodec.encodeEvent(event));

        CoreMessage restoredEnvelope = CoreMessageCodec.decode(CoreMessageCodec.encode(envelope));
        CoreExportEvent restored = CoreExportCodec.decodeEvent(restoredEnvelope, productLine);

        assertThat(restoredEnvelope.header().productLine()).isEqualTo(productLine);
        assertThat(restored.commandFingerprint()).isEqualTo(event.commandFingerprint());
        assertThat(restored.terminalIds()).isEqualTo(event.terminalIds());
        ProductLine mismatch = productLine == ProductLine.SPOT
                ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(restoredEnvelope, mismatch))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("Core export event envelope mismatch");
    }

    @Test
    void preservesCanonicalTerminalIdsAndRejectsDuplicates() {
        assertThat(new CoreExportEvent.TerminalIds(List.of(1L, 2L), List.of(), List.of()).orderIds())
                .containsExactly(1L, 2L);
        assertThatThrownBy(() -> new CoreExportEvent.TerminalIds(List.of(1L, 1L), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreExportEvent.TerminalIds(List.of(2L, 1L), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreExportEvent.TerminalIds(List.of(0L), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyTheCurrentTypedConstructor() {
        assertThat(CoreExportEvent.class.getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(22));
    }

    @Test
    void rejectsAZeroCommandFingerprint() {
        CoreExportEvent valid = event(ProductLine.SPOT, 1, UUID.randomUUID());

        assertThatThrownBy(() -> new CoreExportEvent(valid.exportSequence(), valid.appliedCommandCount(),
                valid.commandId(), valid.commandType(), valid.commandStatus(), valid.resultCode(), valid.userId(),
                valid.commandPayload(), valid.executions(), valid.fundingPayments(), valid.routeVersion(),
                valid.committedCoreSequence(), valid.clusterPosition(), valid.fundsPostings(),
                CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]), valid.terminalIds(),
                valid.previousCoreSequence(), valid.coreSequence(), valid.previousProjectionSequence(),
                valid.projectionSequence(), valid.fundingProgress(), valid.settlementProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command fingerprint must not be zero");
    }

    private static CoreExportEvent event(ProductLine productLine, long sequence, UUID commandId) {
        CoreMessage command = command(productLine, sequence, commandId, 17, new byte[]{1});
        var execution = new CoreExecutionView(71, 72, 17, 18, 60_000, 1);
        var posting = new CoreFundsPostingView("USDT", CoreFundsPostingView.OwnerKind.USER, 17,
                CoreFundsPostingView.Subledger.AVAILABLE, 12);
        return new CoreExportEvent(sequence, sequence, commandId, CoreMessageType.PROBE_INCREMENT,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17, command.payloadUnsafe(), List.of(execution), List.of(),
                CoreRoute.DEFAULT.version(), sequence, sequence, List.of(posting), CommandFingerprint.of(command),
                new CoreExportEvent.TerminalIds(List.of(71L), List.of(), List.of()),
                Math.max(0, sequence - 1), sequence, Math.max(0, sequence - 1), sequence, null, null);
    }

    private static CoreMessage messageForBatchLength(int batchLength) {
        int payloadLength = batchLength - Integer.BYTES * 2 - CoreProtocol.HEADER_LENGTH;
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 0, 1, 1),
                new byte[payloadLength]);
        assertThat(CoreExportCodec.encodeBatch(List.of(message))).hasSize(batchLength);
        return message;
    }

    private static CoreMessage command(ProductLine productLine, long sequence, UUID commandId,
                                       long userId, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                productLine, CommandSource.OPERATIONS, 1, sequence, userId, sequence, sequence), payload);
    }

    private static byte[] withStatusHeader(CoreExportStatus status, byte[] batch) {
        byte[] empty = CoreExportCodec.encodeBatchWithStatus(status, List.of());
        byte[] encoded = Arrays.copyOf(empty, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH + batch.length);
        System.arraycopy(batch, 0, encoded, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH, batch.length);
        return encoded;
    }
}
