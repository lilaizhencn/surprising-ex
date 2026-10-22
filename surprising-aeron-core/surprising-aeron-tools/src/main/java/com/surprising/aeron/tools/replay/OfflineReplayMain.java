package com.surprising.aeron.tools.replay;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class OfflineReplayMain {

    private static final int MAX_MESSAGE_LENGTH = 16 * 1024 * 1024;

    private OfflineReplayMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: OfflineReplayMain <PRODUCT_LINE> <length-prefixed-log>");
        }
        ProductLine productLine = ProductLine.requireExternalCode(args[0]);
        long messages = 0;
        try (TradingCoreRuntime state = new TradingCoreRuntime(productLine);
                DataInputStream input = new DataInputStream(new BufferedInputStream(
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
                int pendingBefore = state.pendingMatching.size();
                long position = messages + 1;
                CoreResponse response = state.apply(message, message.header().submittedAtEpochMillis(), position);
                long directDeadline = System.nanoTime() + 30_000_000_000L;
                while (state.directCommand.active()) {
                    response = state.directCommand.poll(state);
                    if (System.nanoTime() >= directDeadline || Thread.currentThread().isInterrupted())
                        throw new IllegalStateException("direct command replay did not complete");
                    if (response == null) java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
                }
                if (response == null) throw new IllegalStateException("replay command has no response");
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING
                        || state.pendingMatching.size() > pendingBefore) {
                    drainMatching(state, pendingBefore, message, position);
                }
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                    long queryId = state.bookQueries.querySequence(message.header().commandId());
                    if (queryId != 0) {
                        drainQuery(state, queryId);
                    }
                }
                messages++;
            }
            log.info("{}", String.format(java.util.Locale.ROOT,
                    "productLine=%s messages=%d appliedCommandCount=%d stateHash=%016x%n",
                    productLine, messages, state.appliedCommandCount(), state.stateHash()).stripTrailing());
        }
    }

    private static void drainMatching(TradingCoreRuntime state, int pendingBefore,
            CoreMessage message, long position) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (state.pendingMatching.size() > pendingBefore) {
            // Reuse the live Owner's ordered completion path, including admission rejection,
            // Lane receipts and asynchronous Ledger publication.
            int completed = state.commits.commitReadyMatching(64,
                    message.header().submittedAtEpochMillis(), position, false, (sequence, response) -> {});
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("matching replay did not complete");
            if (completed == 0) java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
        }
    }

    private static void drainQuery(TradingCoreRuntime state, long queryId) {
        CoreResponse result = null;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (result == null && System.nanoTime() < deadline) {
            result = state.bookQueries.takeQueryResult(queryId);
            if (result == null) Thread.onSpinWait();
        }
        if (result == null) throw new IllegalStateException("book query replay timed out queryId=" + queryId);
    }
}
