package com.surprising.aeron.tools.export;

import com.surprising.product.api.ProductLine;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;

/** Atomic local recovery state, written only after Kafka acknowledgement. */
record TradeExportCheckpoint(
        ProductLine product, long logPosition, long tradeSequence, byte[] snapshot) {
    private static final int MAGIC = 0x54584331;

    static TradeExportCheckpoint read(Path path, ProductLine expected) throws IOException {
        if (!Files.exists(path)) return new TradeExportCheckpoint(expected, 0, 0, null);
        MessageDigest digest = sha256();
        try (var raw = Files.newInputStream(path);
                var hashing = new DigestInputStream(raw, digest);
                var input = new DataInputStream(hashing)) {
            if (input.readInt() != MAGIC) throw new IOException("invalid trade export checkpoint");
            ProductLine product = ProductLine.valueOf(input.readUTF());
            long position = input.readLong(), sequence = input.readLong();
            int length = input.readInt();
            if (product != expected
                    || position < 0
                    || sequence < 0
                    || length <= 0
                    || length > 1024 * 1024 * 1024
                    || length > Files.size(path) - 32)
                throw new IOException("invalid trade export checkpoint identity or length");
            byte[] snapshot = input.readNBytes(length);
            if (snapshot.length != length) throw new EOFException("truncated trade checkpoint");
            byte[] actual = digest.digest();
            hashing.on(false);
            byte[] expectedHash = input.readNBytes(32);
            if (!MessageDigest.isEqual(actual, expectedHash) || input.read() != -1)
                throw new IOException("trade checkpoint checksum mismatch");
            return new TradeExportCheckpoint(product, position, sequence, snapshot);
        }
    }

    void write(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".next");
        MessageDigest digest = sha256();
        try (var raw = Files.newOutputStream(temp);
                var hashing = new DigestOutputStream(raw, digest);
                var output = new DataOutputStream(hashing)) {
            output.writeInt(MAGIC);
            output.writeUTF(product.name());
            output.writeLong(logPosition);
            output.writeLong(tradeSequence);
            output.writeInt(snapshot.length);
            output.write(snapshot);
            output.flush();
            byte[] checksum = digest.digest();
            hashing.on(false);
            output.write(checksum);
        }
        try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var directory =
                FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
