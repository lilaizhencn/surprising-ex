package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.agrona.DirectBuffer;

final class SectionedCoreSnapshotRecovery {

    private final byte[] envelope = new byte[SectionedCoreSnapshotCodec.ENVELOPE_LENGTH];
    private final byte[] sectionHeader = new byte[SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH];
    private final byte[][] payloads = new byte[SectionedCoreSnapshotCodec.SECTION_COUNT][];
    private final CRC32C checksum = new CRC32C();
    private int envelopePosition;
    private int sectionHeaderPosition;
    private int sectionIndex;
    private int payloadPosition;
    private int totalLength;
    private boolean modeKnown;
    private boolean complete;

    void accept(DirectBuffer source, int offset, int length) {
        if (source == null || offset < 0 || length < 0 || offset > source.capacity() - length) {
            throw new ProtocolException("invalid snapshot fragment");
        }
        int cursor = offset;
        int remaining = length;
        while (remaining > 0) {
            if (complete) throw new ProtocolException("snapshot has trailing garbage");
            if (!modeKnown) {
                int copied = copy(source, cursor, remaining, envelope, envelopePosition);
                envelopePosition += copied;
                cursor += copied;
                remaining -= copied;
                totalLength = Math.addExact(totalLength, copied);
                if (envelopePosition == envelope.length) selectMode();
                continue;
            }
            if (sectionHeaderPosition < sectionHeader.length) {
                int copied = copy(source, cursor, remaining, sectionHeader, sectionHeaderPosition);
                sectionHeaderPosition += copied;
                cursor += copied;
                remaining -= copied;
                totalLength = Math.addExact(totalLength, copied);
                if (sectionHeaderPosition == sectionHeader.length) beginSection();
                continue;
            }
            byte[] payload = payloads[sectionIndex];
            int copied = Math.min(remaining, payload.length - payloadPosition);
            source.getBytes(cursor, payload, payloadPosition, copied);
            if (sectionIndex < SectionedCoreSnapshotCodec.SECTION_COUNT - 1) {
                checksum.update(payload, payloadPosition, copied);
            }
            payloadPosition += copied;
            cursor += copied;
            remaining -= copied;
            totalLength = Math.addExact(totalLength, copied);
            if (payloadPosition == payload.length) endSection();
        }
    }

    CoreProbeState decode(ProductLine expectedProductLine) {
        return components(expectedProductLine).restore(expectedProductLine);
    }

    CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
        return components(expectedProductLine).manifest(expectedProductLine);
    }

    int ownedSectionCount() {
        return sectionIndex;
    }

    int totalLength() {
        return totalLength;
    }

    int allocatedBytes() {
        int allocated = envelope.length + sectionHeader.length;
        for (byte[] payload : payloads) {
            if (payload != null) allocated = Math.addExact(allocated, payload.length);
        }
        return allocated;
    }

    private void selectMode() {
        ByteBuffer buffer = ByteBuffer.wrap(envelope).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != SectionedCoreSnapshotCodec.MAGIC) {
            throw new ProtocolException("invalid snapshot magic");
        }
        int version = Short.toUnsignedInt(buffer.getShort());
        if (version != SectionedCoreSnapshotCodec.VERSION) {
            throw new ProtocolException("unsupported snapshot version: " + version);
        }
        int reserved = Short.toUnsignedInt(buffer.getShort());
        int sectionCount = buffer.getInt();
        if (reserved != 0 || sectionCount != SectionedCoreSnapshotCodec.SECTION_COUNT) {
            throw new ProtocolException("invalid snapshot section count");
        }
        checksum.update(envelope, 0, envelope.length);
        modeKnown = true;
    }

    private void beginSection() {
        ByteBuffer buffer = ByteBuffer.wrap(sectionHeader).order(ByteOrder.LITTLE_ENDIAN);
        int sectionId = buffer.getInt();
        int sectionLength = buffer.getInt();
        if (sectionIndex >= SectionedCoreSnapshotCodec.SECTION_COUNT || sectionId != sectionIndex + 1) {
            throw new ProtocolException("invalid snapshot section order");
        }
        int minimumLength = switch (sectionIndex) {
            case 0 -> SectionedCoreSnapshotCodec.HEADER_LENGTH;
            case 1, 2 -> Integer.BYTES;
            case 3 -> SectionedCoreSnapshotCodec.OUTBOX_FIXED_LENGTH;
            case 4, 5, 6 -> 1;
            case 7 -> SectionedCoreSnapshotCodec.FOOTER_LENGTH;
            default -> throw new ProtocolException("invalid snapshot section count");
        };
        if (sectionLength < minimumLength || sectionLength > SectionedCoreSnapshotCodec.MAX_SECTION_BYTES
                || sectionIndex == 0 && sectionLength != SectionedCoreSnapshotCodec.HEADER_LENGTH
                || sectionIndex == SectionedCoreSnapshotCodec.SECTION_COUNT - 1
                        && sectionLength != SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            throw new ProtocolException("invalid snapshot section length");
        }
        ensureTotalCapacity(sectionLength);
        payloads[sectionIndex] = new byte[sectionLength];
        payloadPosition = 0;
        if (sectionIndex < SectionedCoreSnapshotCodec.SECTION_COUNT - 1) {
            checksum.update(sectionHeader, 0, sectionHeader.length);
        }
    }

    private void endSection() {
        if (sectionIndex == SectionedCoreSnapshotCodec.SECTION_COUNT - 1) {
            long storedChecksum = ByteBuffer.wrap(payloads[sectionIndex])
                    .order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (storedChecksum != checksum.getValue()) {
                throw new ProtocolException("snapshot checksum mismatch");
            }
            complete = true;
        }
        sectionIndex++;
        sectionHeaderPosition = 0;
        payloadPosition = 0;
        Arrays.fill(sectionHeader, (byte) 0);
    }

    private SectionedCoreSnapshotParser.Components components(ProductLine expectedProductLine) {
        ensureComplete();
        return SectionedCoreSnapshotParser.parse(payloads, expectedProductLine);
    }

    private void ensureComplete() {
        if (totalLength == 0) throw new IllegalStateException("incomplete Aeron core snapshot");
        if (!complete || sectionIndex != SectionedCoreSnapshotCodec.SECTION_COUNT) {
            throw new ProtocolException("snapshot is truncated or incomplete");
        }
    }

    private void ensureTotalCapacity(int additionalLength) {
        if (additionalLength < 0
                || totalLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - additionalLength) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }

    private static int copy(DirectBuffer source, int sourceOffset, int available,
                            byte[] destination, int destinationOffset) {
        int copied = Math.min(available, destination.length - destinationOffset);
        source.getBytes(sourceOffset, destination, destinationOffset, copied);
        return copied;
    }
}
