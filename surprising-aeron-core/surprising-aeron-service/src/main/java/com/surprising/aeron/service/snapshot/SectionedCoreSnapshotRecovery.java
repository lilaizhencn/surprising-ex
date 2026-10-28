package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.agrona.DirectBuffer;

/**
 * 按字节片段接收 v30 快照的内部状态机：先封套，再逐个段头/负载，边接收边更新非页尾 CRC；每段只分配对应长度数组，不创建额外全文件数组。接收时已检查总上限，完整后才交给 Parser 解码与配对检查。它不自行调度线程，也不承诺接收异常后可回滚复用。
 */
public final class SectionedCoreSnapshotRecovery {

    /** 当前恢复实例的总接收上限，分片声明与实际接收均受约束。 */
    private final int maximumSnapshotBytes;

    /**
     * 使用类初始化时确定的部署总字节上限创建接收状态。
     */
    public SectionedCoreSnapshotRecovery() {
        this(com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().snapshot().maxBytes());
    }

    /**
     * 校验显式上限至少容纳封套、一个段头及页尾，且不超过可配置最大值；不要求达到系统属性使用的六十四 MiB 下限。
     * @param maximumSnapshotBytes 当前恢复实例允许的总接收字节上限
     */
    public SectionedCoreSnapshotRecovery(int maximumSnapshotBytes) {
        if (maximumSnapshotBytes < SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                + SectionedCoreSnapshotCodec.FOOTER_LENGTH
                || maximumSnapshotBytes > SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("invalid maximum snapshot bytes");
        }
        this.maximumSnapshotBytes = maximumSnapshotBytes;
    }

    /** 固定十二字节封套缓冲，先收齐后选择版本和段数。 */
    private final byte[] envelope = new byte[SectionedCoreSnapshotCodec.ENVELOPE_LENGTH];
    /** 每段复用的八字节头部缓冲，段结束后清零。 */
    private final byte[] sectionHeader = new byte[SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH];
    /** 按段编号存放独立负载数组的引用表，封套校验后创建，负载逐段分配。 */
    private byte[][] payloads;
    /** 累计封套和全部非页尾段头/负载的 CRC32C 状态。 */
    private final CRC32C checksum = new CRC32C();
    /** 封套缓冲已接收字节数。 */
    private int envelopePosition;
    /** 当前段头已接收字节数。 */
    private int sectionHeaderPosition;
    /** 当前待接收的零基段下标，也等于已经完整接收的段数。 */
    private int sectionIndex;
    /** 当前段负载已经接收的字节数。 */
    private int payloadPosition;
    /**  从来源实际拷入内部数组的累计字节数，用于接收预算与完整性检查。 */
    private int totalLength;
    /** 封套格式和段数是否已经校验通过。 */
    private boolean modeKnown;
    /** 页尾已收齐且 CRC 匹配的完成标记。 */
    private boolean complete;
    /** 封套声明并通过范围校验的总段数，包含各账户 Lane 和页尾。 */
    private int sectionCount;

    /**
     * 先检查来源范围及本次片段是否超过剩余总预算，再按封套、段头、负载顺序拷贝并推进计数。收齐非页尾字节就累计 CRC，页尾完成时验证；完整后额外非空字节拒绝，零长输入不推进。异常不撤回此前已接收的部分。
     * @param source 本次片段的来源 DirectBuffer
     * @param offset 本次片段在来源缓冲中的字节偏移
     * @param length 本次可接收的片段字节数
     */
    public void accept(DirectBuffer source, int offset, int length) {
        if (source == null || offset < 0 || length < 0 || offset > source.capacity() - length) {
            throw new ProtocolException("invalid snapshot fragment");
        }
        if (length > maximumSnapshotBytes - totalLength) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
        // 当前来源片段的读取偏移。
        int cursor = offset;
        // 本次来源片段尚未读取的字节数。
        int remaining = length;
        while (remaining > 0) {
            if (complete) throw new ProtocolException("snapshot has trailing garbage");
            if (!modeKnown) {
                // 本轮拷贝到当前目标缓冲的实际字节数。
                int copied = copy(source, cursor, remaining, envelope, envelopePosition);
                envelopePosition += copied;
                cursor += copied;
                remaining -= copied;
                totalLength = Math.addExact(totalLength, copied);
                if (envelopePosition == envelope.length) selectMode();
                continue;
            }
            if (sectionHeaderPosition < sectionHeader.length) {
                // 本轮拷贝到当前目标缓冲的实际字节数。
                int copied = copy(source, cursor, remaining, sectionHeader, sectionHeaderPosition);
                sectionHeaderPosition += copied;
                cursor += copied;
                remaining -= copied;
                totalLength = Math.addExact(totalLength, copied);
                if (sectionHeaderPosition == sectionHeader.length) beginSection();
                continue;
            }
            // 当前正在写入或统计容量的段负载数组。
            byte[] payload = payloads[sectionIndex];
            // 本轮拷贝到当前目标缓冲的实际字节数。
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

    /**
     * 要求字节完整并解析全部组件，随后恢复和激活指定产品线运行时。
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
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

    /**
     * 解析完整组件并提取清单，不激活运行时；无论清单提取成功与否，都关闭本次解析生成的终态保留索引。
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    public CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
        // 本次完整解析生成的组件，提取清单后关闭其终态索引。
        var components = components(expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration.standalone(),
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology());
        try { return components.manifest(expectedProductLine); }
        finally { components.retention().close(); }
    }

    /**
     * 收齐十二字节封套后检查魔数、当前版本、零保留位及段数范围，分配外层负载引用数组并将封套加入 CRC；不接受其他外层版本。
     */
    private void selectMode() {
        // 当前封套或段头字节的小端读取视图。
        ByteBuffer buffer = ByteBuffer.wrap(envelope).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != SectionedCoreSnapshotCodec.MAGIC) {
            throw new ProtocolException("invalid snapshot magic");
        }
        // 从封套读取的无符号十六位外层版本。
        int version = Short.toUnsignedInt(buffer.getShort());
        if (version != SectionedCoreSnapshotCodec.VERSION) {
            throw new ProtocolException("unsupported snapshot version: " + version);
        }
        // 必须为零的封套保留字段。
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

    /**
     * 要求段号严格等于当前位置加一，按段用途检查最小长度及头部/页尾固定长度，再检查总预算并分配当前负载数组；非页尾段头计入 CRC。
     */
    private void beginSection() {
        // 当前封套或段头字节的小端读取视图。
        ByteBuffer buffer = ByteBuffer.wrap(sectionHeader).order(ByteOrder.LITTLE_ENDIAN);
        // 段头记录的一基顺序段编号。
        int sectionId = buffer.getInt();
        // 段头声明的当前负载字节长度。
        int sectionLength = buffer.getInt();
        if (sectionIndex >= sectionCount || sectionId != sectionIndex + 1) {
            throw new ProtocolException("invalid snapshot section order");
        }
        // 依据段用途要求的最小负载长度。
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

    /**
     * 当前负载收齐后，页尾段比较存储的 CRC 数值与累计值并标记完整；随后推进段计数、归零游标并清掉复用段头。CRC 不包含页尾段头和页尾负载自身。
     */
    private void endSection() {
        if (sectionIndex == sectionCount - 1) {
            // 页尾八字节承载的 CRC32C 数值，须等于接收累计值。
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

    /**
     * 先要求接收完整，再交 Parser 解析配对；协议异常保留，其他运行异常包装成带 cause 的 ProtocolException。
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    private SectionedCoreSnapshotParser.Components components(ProductLine expectedProductLine,
            com.surprising.aeron.config.CoreRuntimeConfiguration configuration,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        ensureComplete();
        try {
            return SectionedCoreSnapshotParser.parse(payloads, expectedProductLine, configuration.fundsIndexDirectory(),
                    deploymentTopology);
        } catch (/* 解析组件抛出的异常，协议异常保留，其他运行异常带 cause 包装。 */ ProtocolException exception) {
            throw exception;
        } catch (/* 解析组件抛出的异常，协议异常保留，其他运行异常带 cause 包装。 */ RuntimeException exception) {
            throw new ProtocolException("invalid snapshot component: " + exception.getMessage(), exception);
        }
    }

    /**
     * 完全没有字节时抛未完成状态异常，其他未收齐或段数不符抛协议异常；不在这里再次计算 CRC。
     */
    private void ensureComplete() {
        if (totalLength == 0) throw new IllegalStateException("incomplete Aeron core snapshot");
        if (!complete || sectionIndex != sectionCount) {
            throw new ProtocolException("snapshot is truncated or incomplete");
        }
    }

    /**
     * 在当前已接收字节基础上检查额外负载不会超过总预算，用减法避免累计溢出；不提前预留后续尚未收到的其他段。
     * @param additionalLength 即将分配的当前段负载字节数
     */
    private void ensureTotalCapacity(int additionalLength) {
        if (additionalLength < 0
                || totalLength > maximumSnapshotBytes - additionalLength) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }

    /**
     * 最多拷贝可用来源字节与目标剩余容量的较小值，返回实际数量，由上层推进游标。
     * @param source 本次片段的来源 DirectBuffer
     * @param sourceOffset 当前待读取的来源字节偏移
     * @param available 来源片段本次尚未读取的字节数
     * @param destination 目标封套或段头数组
     * @param destinationOffset 目标数组已有字节数量
     */
    private static int copy(DirectBuffer source, int sourceOffset, int available,
                            byte[] destination, int destinationOffset) {
        // 本轮拷贝到当前目标缓冲的实际字节数。
        int copied = Math.min(available, destination.length - destinationOffset);
        source.getBytes(sourceOffset, destination, destinationOffset, copied);
        return copied;
    }
}
