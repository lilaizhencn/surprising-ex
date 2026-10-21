package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * 验证直接缓冲解码的独立消息、可复用读取器和借用槽三种生命周期；重点检查变长载荷复用时，旧容量尾部不进入编码、相等性或请求指纹，未退休槽也不会被覆盖。
 */
class CoreMessageFlyweightDecoderTest {

    /**
     * 让订单载荷长度在一千零二十五到二千零四十八字节之间循环两轮，每次归还后复用同一槽和底层数组；核对有效长度、相等性、哈希、编码及指纹都与独立消息相同。
     */
    @Test void varyingOrderLengthsReuseCapacityAndExcludeStaleTailFromWireAndFingerprint() {
        // 本场景实际使用的解码器及其固定复用缓存
        var decoder = new CoreMessageFlyweightDecoder();
        // 前一次归还的借用消息，供下次核对同对象复用
        CoreMessage previous = null;
        // 前一次借用消息的底层载荷数组引用
        byte[] backing = null;
        for (/* 完整走过变长订单长度范围的轮次 */ int round = 0; round < 2; round++) {
            for (/* 本轮有效载荷长度，不是预分配数组容量 */ int length = 1025; length <= 2048; length++) {
                // 本轮准备的输入载荷，或原槽已复制的底层数组引用
                byte[] payload = new byte[length];
                java.util.Arrays.fill(payload, (byte) (length & 255));
                // 与当前借用消息比较的独立输入消息或载荷副本
                var expected = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                        new UUID(21, length), ProductLine.SPOT, CommandSource.GATEWAY,
                        21, length, 7, 1, length), payload);
                // 本轮输入的完整协议帧
                byte[] frame = CoreMessageCodec.encode(expected);
                // 本轮取得解码租约的消息，finally 中归还
                var borrowed = decoder.decodeBorrowed(new UnsafeBuffer(frame), 0, frame.length);
                try {
                    if (previous != null) {
                        assertThat(borrowed).isSameAs(previous);
                        assertThat(borrowed.payloadUnsafe()).isSameAs(backing);
                    }
                    assertThat(borrowed.payloadLength()).isEqualTo(length);
                    assertThat(borrowed).isEqualTo(expected);
                    assertThat(borrowed.hashCode()).isEqualTo(expected.hashCode());
                    assertThat(CoreMessageCodec.encode(borrowed)).containsExactly(frame);
                    assertThat(com.surprising.aeron.protocol.CommandFingerprint.of(borrowed))
                            .isEqualTo(com.surprising.aeron.protocol.CommandFingerprint.of(expected));
                    previous = borrowed;
                    backing = borrowed.payloadUnsafe();
                } finally { borrowed.releaseDecoderSlot(); }
            }
        }
    }

    /**
     * 先建立多种管理命令长度桶，反复借还时各自复用数组；再长期借住其中一条并轮换超过默认槽数的其他长度，要求被借消息的字节和 UUID 不变。
     */
    @Test void mixedPayloadLengthsReuseTheirOwnBuffersWithoutChangingLeasedFrames() {
        // 本场景实际使用的解码器及其固定复用缓存
        var decoder = new CoreMessageFlyweightDecoder();
        // 需要建立独立精确长度桶的管理命令载荷长度集合
        int[] sizes = {0, 1, 8, 31, 256, 2048, 8192};
        // 各管理命令对应的完整编码帧
        var frames = new byte[sizes.length][];
        // 各长度桶第一次分配的底层数组引用
        var payloads = new byte[sizes.length][];
        for (/* 当前检查的长度桶下标 */ int i = 0; i < sizes.length; i++) {
            // 当前精确长度桶准备写入输入帧的原始载荷数组
            var value = new byte[sizes[i]];
            java.util.Arrays.fill(value, (byte) (i + 1));
            frames[i] = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, new UUID(1, i + 1), ProductLine.SPOT,
                    CommandSource.GATEWAY, 1, i + 1, 7, 1, 1), value));
            // 本长度桶第一次借到的消息，记录数组后归还
            var first = decoder.decodeBorrowed(new UnsafeBuffer(frames[i]), 0, frames[i].length);
            payloads[i] = first.payloadUnsafe();
            first.releaseDecoderSlot();
        }
        for (/* 重复验证所有长度桶复用的轮次 */ int generation = 0; generation < 1024; generation++) {
            for (/* 当前检查的长度桶下标 */ int i = 0; i < sizes.length; i++) {
                // 本轮借用消息或准备的输入字节，使用后按解码租约归还
                var value = decoder.decodeBorrowed(new UnsafeBuffer(frames[i]), 0, frames[i].length);
                assertThat(value.payloadUnsafe()).isSameAs(payloads[i]);
                assertThat(value.payloadLength()).isEqualTo(sizes[i]);
                if (sizes[i] != 0) assertThat(value.payloadUnsafe()[sizes[i] - 1]).isEqualTo((byte) (i + 1));
                value.releaseDecoderSlot();
            }
        }
        // 故意长期保留解码租约的消息，其他槽轮换不能覆盖它
        var held = decoder.decodeBorrowed(new UnsafeBuffer(frames[2]), 0, frames[2].length);
        // 与当前借用消息比较的独立输入消息或载荷副本
        byte[] expected = held.payload();
        for (/* 本轮有效载荷长度，不是预分配数组容量 */ int length = 1; length < 640; length++) {
            // 本轮输入的完整协议帧
            var frame = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, new UUID(2, length), ProductLine.SPOT,
                    CommandSource.GATEWAY, 1, length, 7, 1, 1), new byte[length]));
            // 本轮借用消息或准备的输入字节，使用后按解码租约归还
            var value = decoder.decodeBorrowed(new UnsafeBuffer(frame), 0, frame.length);
            assertThat(value).isNotSameAs(held);
            value.releaseDecoderSlot();
        }
        assertThat(held.payloadUnsafe()).containsExactly(expected);
        assertThat(held.header().commandId()).isEqualTo(new UUID(1, 3));
        held.releaseDecoderSlot();
    }

    /**
     * 两个解码器的读取视图必须不同；同一解码器的 reader 可重新绑定另一帧，而此前 decode 返回的独立消息保持不变。
     */
    @Test
    void readersBelongToTheirDecoderAndCanBeReusedWithoutChangingOwnedMessages() {
        // 第一条现货余额命令的完整编码帧
        byte[] first = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7))));
        // 第二条线性永续余额命令的完整编码帧
        byte[] second = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, 43, 1002,
                123_456_790, 100), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 9))));
        // 第一个独立解码器及其读取器
        var decoderA = new CoreMessageFlyweightDecoder();
        // 第二个独立解码器及其读取器
        var decoderB = new CoreMessageFlyweightDecoder();
        // 第一个解码器当前复用的头部读取视图
        var viewA = decoderA.reader(new UnsafeBuffer(first), 0, first.length);
        // 第二个解码器的独立读取视图
        var viewB = decoderB.reader(new UnsafeBuffer(second), 0, second.length);
        assertThat(viewA).isNotSameAs(viewB);
        assertThat(viewA.userId()).isEqualTo(1001);
        // 第一个解码器创建的独立消息，不随 reader 重绑改变
        var owned = decoderA.decode(new UnsafeBuffer(first), 0, first.length);
        assertThat(decoderA.reader(new UnsafeBuffer(second), 0, second.length)).isSameAs(viewA);
        assertThat(owned).isEqualTo(CoreMessageCodec.decode(first));
    }

    /**
     * 同时借满五百一十二槽后额外解码，不能覆盖已借消息；归还后可重新借回原槽与数组，事先复制的不可变 Header 仍独立稳定，输入数组改写也不影响已复制载荷。
     */
    @Test void borrowedFramesWaitForRetirementAndKeepResponseHeadersStable() {
        // 与当前借用消息比较的独立输入消息或载荷副本
        var expected = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, 2), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001, 1, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
        // 当前命令的原始编码字节
        var encoded = CoreMessageCodec.encode(expected);
        // 包裹原编码帧的 DirectBuffer
        var buffer = new UnsafeBuffer(encoded);
        // 本场景实际使用的解码器及其固定复用缓存
        var decoder = new CoreMessageFlyweightDecoder();
        // 同时保留解码租约的五百一十二条消息
        var messages = new CoreMessage[512];
        for (/* 当前缓存槽或重复借还的循环下标 */ int index = 0; index < messages.length; index++) messages[index] = decoder.decodeBorrowed(buffer, 0, encoded.length);
        // 借用消息头的不可变复制值，用来验证独立身份稳定
        var headerForEgress = messages[0].header().immutableValue();
        // 本轮准备的输入载荷，或原槽已复制的底层数组引用
        var payload = messages[0].payloadUnsafe();
        // 全部槽被借住后额外解码出的独立消息
        var overflow = decoder.decodeBorrowed(buffer, 0, encoded.length);
        assertThat(overflow).isNotSameAs(messages[0]).isEqualTo(expected);
        for (var /* 当前要归还的借用消息，或独立编码输入消息 */ message : messages) message.releaseDecoderSlot();
        // 归还原消息后再次借回的同一个缓存槽，未找到时为空
        CoreMessage reused = null;
        for (/* 当前缓存槽或重复借还的循环下标 */ int index = 0; index < 512; index++) {
            // 本轮借用消息或准备的输入字节，使用后按解码租约归还
            var value = decoder.decodeBorrowed(buffer, 0, encoded.length);
            if (value == messages[0]) reused = value;
            assertThat(value).isEqualTo(expected);
        }
        assertThat(reused).isSameAs(messages[0]);
        assertThat(reused.payloadUnsafe()).isSameAs(payload);
        assertThat(headerForEgress).isEqualTo(expected.header());
        assertThat(headerForEgress).isNotSameAs(reused.header());
        encoded[CoreProtocol.HEADER_LENGTH] = 0;
        assertThat(reused.payloadUnsafe()).isEqualTo(expected.payloadUnsafe());
        for (var /* 当前要归还的借用消息，或独立编码输入消息 */ message : messages) message.releaseDecoderSlot();
    }

    /**
     * 把帧放在带前后空间的缓冲中，从非零偏移解码；改写原帧载荷后，独立消息仍等于原输入。
     */
    @Test
    void decodesDirectBufferIntoOwnedPayload() {
        // 当前要归还的借用消息，或独立编码输入消息
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
        // 当前命令的原始编码字节
        byte[] encoded = CoreMessageCodec.encode(message);
        // 把协议帧放在非零偏移的外层字节数组
        byte[] framed = new byte[encoded.length + 8];
        System.arraycopy(encoded, 0, framed, 4, encoded.length);

        // 复制有效载荷后返回的独立消息
        CoreMessage decoded = new CoreMessageFlyweightDecoder().decode(new UnsafeBuffer(framed), 4, encoded.length);
        framed[4 + CoreProtocol.HEADER_LENGTH] = 0;

        assertThat(decoded).isEqualTo(message);
    }
}
