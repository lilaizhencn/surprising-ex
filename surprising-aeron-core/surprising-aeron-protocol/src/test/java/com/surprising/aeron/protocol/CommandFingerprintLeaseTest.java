package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class CommandFingerprintLeaseTest {
    @Test void canonicalDigestMatchesThePublishedWireFieldVector() {
        // Canonical-v1: schema8, PROBE1, SPOT1, shard0/route3, gateway1, user7,
        // payloadLength8 then LE delta1. Expected SHA-256 was computed independently.
        var expected = java.util.HexFormat.of().parseHex(
                "f1d0788b350d61d3c37ff2657c5d76c1cc4fb8f73b8b1f026237953df1c6e330");
        var calculator = new CommandFingerprint.Calculator();
        var message = probe(ProductLine.SPOT, 1);
        assertThat(calculator.of(message).bytes()).containsExactly(expected);
        CoreMessage slot = CoreMessage.decoderSlot();
        load(slot, message);
        assertThat(calculator.ofBorrowed(slot).bytes()).containsExactly(expected);
        slot.releaseDecoderSlot();
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void borrowedDigestMatchesCanonicalBytesAndRetainedValuesSurviveSlotReuse(ProductLine line) {
        var calculator = new CommandFingerprint.Calculator();
        CoreMessage slot = CoreMessage.decoderSlot();
        var first = probe(line, 1);
        load(slot, first);
        var expected = calculator.of(first);
        var borrowed = calculator.ofBorrowed(slot);
        assertThat(borrowed.bytes()).containsExactly(expected.bytes());
        assertThat(borrowed.hashCode()).isEqualTo(expected.hashCode());
        assertThat(calculator.ofBorrowed(slot)).isSameAs(borrowed);
        var retained = borrowed.copyIntoSlot(null);
        var exposed = borrowed.immutableValue();
        var publicValue = calculator.of(slot);
        assertThat(publicValue.isSlotValue()).isFalse();
        assertThatThrownBy(() -> borrowed.copyIntoSlot(expected)).isInstanceOf(IllegalArgumentException.class);
        slot.releaseDecoderSlot();
        assertThatThrownBy(borrowed::bytes).isInstanceOf(IllegalStateException.class);
        assertThat(retained).isEqualTo(expected);
        for (int i = 2; i < 2048; i++) {
            var next = probe(line, i);
            load(slot, next);
            var nextDigest = calculator.ofBorrowed(slot);
            assertThat(nextDigest).isSameAs(borrowed).isEqualTo(calculator.of(next));
            assertThat(retained).isEqualTo(expected);
            slot.releaseDecoderSlot();
        }
        assertThat(exposed).isEqualTo(expected);
        assertThat(publicValue).isEqualTo(expected);
    }

    @Test void publicOwnedValuesAndOwnedInputPendingResponsesRemainIndependent() {
        var calculator = new CommandFingerprint.Calculator();
        var message = probe(ProductLine.SPOT, 1);
        var original = calculator.of(message);
        var borrowedApiOnOwnedInput = calculator.ofBorrowed(message);
        assertThat(borrowedApiOnOwnedInput.isSlotValue()).isFalse();
        message.payloadUnsafe()[0] ^= 1;
        assertThat(calculator.of(message)).isNotEqualTo(original);
        assertThat(borrowedApiOnOwnedInput).isEqualTo(original);
        assertThat(original.immutableValue()).isSameAs(original);
        var first = message.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 1);
        var next = message.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 2);
        assertThat(next).isNotSameAs(first);
        assertThat(first.appliedCommandCount()).isOne();
    }

    @Test void pendingAndTerminalResponseCellsStaySeparateForTheWholeInputLease() {
        CoreMessage slot = CoreMessage.decoderSlot();
        load(slot, probe(ProductLine.SPOT, 1));
        var pending = slot.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 7);
        var terminal = slot.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, new byte[]{1, 2, 3}, 0, 3);
        assertThat(pending).isNotSameAs(terminal);
        assertThat(pending.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        assertThat(terminal.data()).containsExactly(1, 2, 3);
        assertThat(slot.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 7)).isSameAs(pending);
        assertThatThrownBy(() -> slot.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 8))
                .isInstanceOf(IllegalStateException.class);
        slot.releaseDecoderSlot();
        assertThatThrownBy(() -> slot.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 7))
                .isInstanceOf(IllegalStateException.class);
        load(slot, probe(ProductLine.SPOT, 2));
        assertThat(slot.matchingPendingResponse(CoreResultCode.MATCHING_PENDING, 8)).isSameAs(pending);
        var nextTerminal = slot.ownedResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                CoreResultCode.INVALID_COMMAND, 8, CoreMessage.EMPTY_PAYLOAD, 0, 0);
        assertThat(nextTerminal).isSameAs(terminal);
        assertThat(nextTerminal.dataLength()).isZero();
        slot.releaseDecoderSlot();
    }

    private static CoreMessage probe(ProductLine line, long id) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, new UUID(16, id),
                line, CommandSource.GATEWAY, 16, id, 7, 1, id), CoreProtocol.probePayload(id));
    }

    private static void load(CoreMessage target, CoreMessage source) {
        assertThat(target.acquireDecoderSlot()).isTrue();
        var h = source.header();
        target.decoderHeader(h.schemaVersion(), h.kind(), h.messageType(), h.commandId(), h.productLine(), h.route(),
                h.source(), h.sourceId(), h.sourceSequence(), h.userId(), h.submittedAtEpochMillis(), h.correlationId(), h.traceId());
        source.copyPayloadTo(target.decoderPayloadBuffer(source.payloadLength()), 0);
    }
}
