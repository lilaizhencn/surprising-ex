package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.agrona.DirectBuffer;

/** 按字节片段接收 v30 快照的内部状态机：先封套，再逐个段头/负载，边接收边更新非页尾 CRC。它不自行调度线程，也不承诺接收异常后可回滚复用。 */
public final class SectionedCoreSnapshotRecovery {

    /** 当前恢复实例的总接收上限，分片声明与实际接收均受约束。 */
    private final int maximumSnapshotBytes;

    /** 使用类初始化时确定的部署总字节上限创建接收状态。 */
    public SectionedCoreSnapshotRecovery() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes());
    }

    /** 校验显式上限至少容纳封套、一个段头及页尾，且不超过可配置最大值。 */
    public SectionedCoreSnapshotRecovery(int maximumSnapshotBytes) {
        if (maximumSnapshotBytes < SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                + SectionedCoreSnapshotCodec.FOOTER_LENGTH
                || maximumSnapshotBytes > SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("invalid maximum snapshot bytes");
        }
        this.maximumSnapshotBytes = maximumSnapshotBytes;
    }

    private final byte[] envelope = new byte[SectionedCoreSnapshotCodec.ENVELOPE_LENGTH];
    private final byte[] sectionHeader = new byte[SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH];
    private byte[][] payloads;
    private final CRC32C checksum = new CRC32C();
    private int envelopePosition;
    private int sectionHeaderPosition;
    private int sectionIndex;
    private int payloadPosition;
    private int totalLength;
    private boolean modeKnown;
    private boolean complete;
    private int sectionCount;

    /** 先检查来源范围及本次片段是否超过剩余总预算，再按封套、段头、负载顺序拷贝并推进计数。 */
    public void accept(DirectBuffer source, int offset, int length) {
        if (source == null || offset < 0 || length < 0 || offset > source.capacity() - length) {
            throw new ProtocolException("invalid snapshot fragment");
        }
        if (length > maximumSnapshotBytes - totalLength) {
            throw new ProtocolException("core snapshot exceeds maximum size");
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
            if (sectionIndex < sectionCount - 1) {
                checksum.update(payload, payloadPosition, copied);
            }
            payloadPosition += copied;
            cursor += copied;
            remaining -= copied;
            totalLength = Math.addExact(totalLength, copied);
            if (payloadPosition == payload.length) endSection();
        }
    }

    /** 要求字节完整并解析全部组件，随后恢复和激活指定产品线运行时。 */
    public TradingCoreRuntime decode(ProductLine expectedProductLine) {
        return decode(expectedProductLine, com.surprising.aeron.config.CoreRuntimeConfiguration.standalone());
    }

    public TradingCoreRuntime decode(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        return decode(expectedProductLine, configuration,
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
    }

    public TradingCoreRuntime decode(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return components(expectedProductLine, configuration, deploymentTopology)
                .restore(expectedProductLine, configuration, true);
    }

    /** 节点启动恢复只重建被动状态，外层 Owner 使用已绑定的 affinity 首次激活。 */
    public TradingCoreRuntime decodePassive(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration) {
        return decodePassive(expectedProductLine, configuration,
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
    }

    public TradingCoreRuntime decodePassive(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        return components(expectedProductLine, configuration, deploymentTopology)
                .restore(expectedProductLine, configuration, false);
    }

    /** 解析完整组件并提取清单，不激活运行时。 */
    public CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
        var components = components(expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration.standalone(),
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
        try { return components.manifest(expectedProductLine); }
        finally { components.retention().close(); }
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
        sectionCount = buffer.getInt();
        if (reserved != 0 || sectionCount < SectionedCoreSnapshotCodec.BASE_SECTION_COUNT + 1
                || sectionCount > SectionedCoreSnapshotCodec.MAX_SECTION_COUNT) {
            throw new ProtocolException("invalid snapshot section count");
        }
        payloads = new byte[sectionCount][];
        checksum.update(envelope, 0, envelope.length);
        modeKnown = true;
    }

    private void beginSection() {
        ByteBuffer buffer = ByteBuffer.wrap(sectionHeader).order(ByteOrder.LITTLE_ENDIAN);
        int sectionId = buffer.getInt();
        int sectionLength = buffer.getInt();
        if (sectionIndex >= sectionCount || sectionId != sectionIndex + 1) {
            throw new ProtocolException("invalid snapshot section order");
        }
        int minimumLength = switch (sectionIndex) {
            case 0 -> SectionedCoreSnapshotCodec.HEADER_LENGTH;
            case 1, 2 -> Integer.BYTES;
            case 3, 4, 5, 6, 7 -> 1;
            default -> sectionIndex == sectionCount - 1
                    ? SectionedCoreSnapshotCodec.FOOTER_LENGTH
                    : Integer.BYTES * 2 + Long.BYTES * 5;
        };
        if (sectionLength < minimumLength || sectionLength > SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES
                || sectionIndex == 0 && sectionLength != SectionedCoreSnapshotCodec.HEADER_LENGTH
                || sectionIndex == sectionCount - 1
                        && sectionLength != SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            throw new ProtocolException("invalid snapshot section length");
        }
        ensureTotalCapacity(sectionLength);
        payloads[sectionIndex] = new byte[sectionLength];
        payloadPosition = 0;
        if (sectionIndex < sectionCount - 1) {
            checksum.update(sectionHeader, 0, sectionHeader.length);
        }
    }

    private void endSection() {
        if (sectionIndex == sectionCount - 1) {
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

    private SectionedCoreSnapshotParser.Components components(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        ensureComplete();
        try {
            return SectionedCoreSnapshotParser.parse(payloads, expectedProductLine, deploymentTopology);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProtocolException("invalid snapshot component: " + exception.getMessage(), exception);
        }
    }

    private void ensureComplete() {
        if (totalLength == 0) throw new IllegalStateException("incomplete Aeron core snapshot");
        if (!complete || sectionIndex != sectionCount) {
            throw new ProtocolException("snapshot is truncated or incomplete");
        }
    }

    /** 在当前已接收字节基础上检查额外负载不会超过总预算，用减法避免累计溢出。 */
    private void ensureTotalCapacity(int additionalLength) {
        if (additionalLength < 0
                || totalLength > maximumSnapshotBytes - additionalLength) {
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
