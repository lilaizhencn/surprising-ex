package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.CoreProbeState;
import com.surprising.product.api.ProductLine;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OfflineReplayMain {

    private static final int MAX_MESSAGE_LENGTH = 16 * 1024 * 1024;

    private OfflineReplayMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: OfflineReplayMain <PRODUCT_LINE> <length-prefixed-log>");
        }
        ProductLine productLine = ProductLine.requireExternalCode(args[0]);
        CoreProbeState state = new CoreProbeState(productLine);
        long messages = 0;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(Path.of(args[1]))))) {
            while (true) {
                int length;
                try {
                    length = Integer.reverseBytes(input.readInt());
                } catch (EOFException endOfFile) {
                    break;
                }
                if (length <= 0 || length > MAX_MESSAGE_LENGTH) {
                    throw new IllegalArgumentException("invalid replay message length: " + length);
                }
                byte[] encoded = input.readNBytes(length);
                if (encoded.length != length) {
                    throw new EOFException("truncated replay message: expected=" + length
                            + ", actual=" + encoded.length);
                }
                CoreMessage message = CoreMessageCodec.decode(encoded);
                int pendingBefore = state.pendingMatchingCount();
                CoreResponse response = state.apply(message);
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING
                        || state.pendingMatchingCount() > pendingBefore) {
                    drainMatching(state, pendingBefore, message);
                }
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                    long queryId = state.querySequence(message.header().commandId());
                    if (queryId != 0) {
                        drainQuery(state, queryId);
                    }
                }
                messages++;
            }
        }
        state.close();
        System.out.printf("productLine=%s messages=%d appliedCommandCount=%d stateHash=%016x%n",
                productLine, messages, state.appliedCommandCount(), state.stateHash());
    }

    private static void drainMatching(CoreProbeState state, int pendingBefore, CoreMessage message) {
        while (state.pendingMatchingCount() > pendingBefore) {
            long sequence = state.firstPendingMatchingSequence();
            CoreMatchingResult matching = null;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (matching == null && System.nanoTime() < deadline) {
                matching = state.takeMatchingResult(sequence);
                if (matching == null) Thread.onSpinWait();
            }
            if (matching == null) {
                throw new IllegalStateException("matching replay timed out sequence=" + sequence);
            }
            CoreResponse completed = state.completeMatching(sequence, matching,
                    message.header().submittedAtEpochMillis(), sequence);
            if (completed == null) {
                throw new IllegalStateException("matching replay completion lost sequence=" + sequence);
            }
        }
    }

    private static void drainQuery(CoreProbeState state, long queryId) {
        CoreResponse result = null;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeQueryResult(queryId);
            if (result == null) Thread.onSpinWait();
        }
        if (result == null) throw new IllegalStateException("book query replay timed out queryId=" + queryId);
    }
}
