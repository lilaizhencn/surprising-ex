package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreProbeStateTest {

    @Test
    void appliesCommandOnceAndReturnsOriginalDuplicateResult() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreMessage command = command(UUID.randomUUID(), 1, 7);

        var applied = state.apply(command);
        var duplicate = state.apply(command);

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.appliedCommandCount()).isEqualTo(1);
        assertThat(duplicate.stateHash()).isEqualTo(applied.stateHash());
        assertThat(state.probeValue()).isEqualTo(7);
    }

    @Test
    void sourceHighWatermarkSurvivesIdempotencyWindowEviction() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
            assertThat(state.apply(command(UUID.randomUUID(), sequence, 1)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }

        var staleRetry = state.apply(command(UUID.randomUUID(), 1, 100));

        assertThat(staleRetry.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(state.appliedCommandCount()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
        assertThat(state.probeValue()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
    }

    @Test
    void snapshotRoundTripPreservesStateHashAndDeduplication() {
        CoreProbeState original = new CoreProbeState(ProductLine.INVERSE_DELIVERY);
        CoreMessage first = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 1, 11);
        CoreMessage second = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 2, -3);
        original.apply(first);
        original.apply(second);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.INVERSE_DELIVERY, original.snapshot());

        assertThat(restored.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(restored.probeValue()).isEqualTo(8);
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.apply(first).status()).isEqualTo(ResponseStatus.DUPLICATE);
    }

    @Test
    void rejectsAnotherProductLineWithoutMutatingState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);

        var result = state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 10));

        assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(state.appliedCommandCount()).isZero();
        assertThat(state.probeValue()).isZero();
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long delta) {
        return command(ProductLine.SPOT, commandId, sourceSequence, delta);
    }

    private static CoreMessage command(
            ProductLine productLine,
            UUID commandId,
            long sourceSequence,
            long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                productLine, CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence),
                CoreProtocol.probePayload(delta));
    }
}
