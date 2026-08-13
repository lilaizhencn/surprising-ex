package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreMessageCodec;
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
                state.apply(CoreMessageCodec.decode(encoded));
                messages++;
            }
        }
        System.out.printf("productLine=%s messages=%d appliedCommandCount=%d stateHash=%016x%n",
                productLine, messages, state.appliedCommandCount(), state.stateHash());
    }
}
