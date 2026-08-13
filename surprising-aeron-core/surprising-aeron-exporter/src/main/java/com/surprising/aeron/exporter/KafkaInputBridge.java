package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class KafkaInputBridge {

    private final ProductLine productLine;
    private final CoreCommandGateway core;

    public KafkaInputBridge(ProductLine productLine, CoreCommandGateway core) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.core = Objects.requireNonNull(core, "core");
    }

    public CoreResponse submit(KafkaInput input, CoreMessageType type, long userId, byte[] payload) {
        Objects.requireNonNull(input, "input");
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("Kafka input must map to a core command");
        }
        long sourceSequence = Math.incrementExact(input.offset());
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId(input), productLine,
                CommandSource.KAFKA_INPUT_BRIDGE, sourceId(input.topic(), input.partition()),
                sourceSequence, userId, input.timestampEpochMillis(), input.offset()), payload);
        return core.submit(message);
    }

    public static boolean mayCommitOffset(CoreResponse response) {
        return response != null && response.resultCode() != CoreResultCode.EXPORT_BACKLOG_FULL
                && (response.status() == ResponseStatus.APPLIED
                || response.status() == ResponseStatus.REJECTED
                || response.status() == ResponseStatus.DUPLICATE);
    }

    static long sourceId(String topic, int partition) {
        byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(topicBytes.length + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).put(topicBytes).putInt(partition);
        long hash = 0xcbf29ce484222325L;
        for (byte value : input.array()) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static UUID commandId(KafkaInput input) {
        return UUID.nameUUIDFromBytes((input.topic() + ":" + input.partition() + ":" + input.offset())
                .getBytes(StandardCharsets.UTF_8));
    }

    public record KafkaInput(String topic, int partition, long offset, long timestampEpochMillis) {
        public KafkaInput {
            if (topic == null || topic.isBlank() || partition < 0 || offset < 0 || timestampEpochMillis < 0) {
                throw new IllegalArgumentException("invalid Kafka input position");
            }
        }
    }
}
