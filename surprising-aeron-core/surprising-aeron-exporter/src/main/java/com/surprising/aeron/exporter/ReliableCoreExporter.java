package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportBatch;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class ReliableCoreExporter {

    private static final long SOURCE_ID = 0x4558504f52544552L;

    private final ProductLine productLine;
    private final CoreCommandGateway core;
    private final CoreExportSink sink;
    private final int batchSize;
    private final AtomicLong correlations = new AtomicLong();

    public ReliableCoreExporter(
            ProductLine productLine,
            CoreCommandGateway core,
            CoreExportSink sink,
            int batchSize) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.core = Objects.requireNonNull(core, "core");
        this.sink = Objects.requireNonNull(sink, "sink");
        CoreExportCodec.encodeBatchQuery(batchSize);
        this.batchSize = batchSize;
    }

    public ExportCycleResult exportOnce() throws Exception {
        return exportOnce(null);
    }

    private ExportCycleResult exportOnce(CoreExportStatus before) throws Exception {
        CoreResponse batchResponse = core.submit(query(CoreMessageType.EXPORT_BATCH_QUERY,
                CoreExportCodec.encodeBatchQuery(batchSize)));
        requireOk(batchResponse, "export batch query");
        CoreExportBatch batch = CoreExportCodec.decodeBatchResponse(batchResponse.data());
        List<CoreMessage> events = batch.events();
        if (events.isEmpty()) {
            if (before != null && before.pendingCount() > 0) {
                throw new IllegalStateException("non-empty export backlog returned an empty batch");
            }
            return new ExportCycleResult(0, before == null ? status() : before);
        }
        if (before != null && batch.acknowledgedSequence() != before.acknowledgedSequence()) {
            throw new IllegalStateException("export acknowledgement moved during batch query");
        }
        validateContiguous(events, batch.acknowledgedSequence() + 1);
        sink.publish(productLine, events);
        long throughSequence = CoreExportCodec.decodeEvent(events.getLast().payload()).exportSequence();
        CoreResponse ackResponse = core.submit(ack(throughSequence));
        if (ackResponse.commandStatus() != ResponseStatus.APPLIED
                && ackResponse.commandStatus() != ResponseStatus.DUPLICATE) {
            throw new IllegalStateException("export ack rejected: " + ackResponse.resultCode());
        }
        CoreExportStatus after = ackResponse.data().length == 0
                ? status() : CoreExportCodec.decodeStatus(ackResponse.data());
        return new ExportCycleResult(events.size(), after);
    }

    public CoreExportStatus status() {
        CoreResponse response = core.submit(query(CoreMessageType.EXPORT_STATUS_QUERY, new byte[0]));
        requireOk(response, "export status query");
        return CoreExportCodec.decodeStatus(response.data());
    }

    public ExportHealth health() {
        CoreExportStatus current = status();
        return new ExportHealth(true, current.acceptingCommands(), current);
    }

    public CoreExportStatus drain(int maxCycles) throws Exception {
        if (maxCycles <= 0) {
            throw new IllegalArgumentException("maxCycles must be positive");
        }
        CoreExportStatus current = status();
        for (int cycle = 0; cycle < maxCycles && current.pendingCount() > 0; cycle++) {
            current = exportOnce(current).status();
        }
        if (current.pendingCount() > 0) {
            throw new IllegalStateException("export drain exceeded max cycles; pending=" + current.pendingCount());
        }
        return current;
    }

    private CoreMessage query(CoreMessageType type, byte[] payload) {
        long correlation = correlations.incrementAndGet();
        UUID queryId = UUID.nameUUIDFromBytes((productLine + ":query:" + correlation)
                .getBytes(StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.query(type, queryId, productLine,
                CommandSource.OPERATIONS, SOURCE_ID, 0, 0,
                System.currentTimeMillis(), correlation), payload);
    }

    private CoreMessage ack(long throughSequence) {
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":export-ack:" + throughSequence)
                .getBytes(StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT, commandId,
                productLine, CommandSource.OPERATIONS, SOURCE_ID, throughSequence, 0,
                System.currentTimeMillis(), correlations.incrementAndGet()),
                CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));
    }

    private static void validateContiguous(List<CoreMessage> events, long expectedFirst) {
        long expected = expectedFirst;
        for (CoreMessage message : events) {
            long actual = CoreExportCodec.decodeEvent(message.payload()).exportSequence();
            if (actual != expected) {
                throw new IllegalStateException("non-contiguous export batch: expected=" + expected
                        + ", actual=" + actual);
            }
            expected = Math.incrementExact(expected);
        }
    }

    private static void requireOk(CoreResponse response, String operation) {
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(operation + " failed: " + response.resultCode());
        }
    }

    public record ExportCycleResult(int publishedEvents, CoreExportStatus status) {
        public ExportCycleResult {
            if (publishedEvents < 0 || status == null) {
                throw new IllegalArgumentException("invalid export cycle result");
            }
        }
    }

    public record ExportHealth(boolean clusterConnected, boolean acceptingCommands, CoreExportStatus status) {
        public ExportHealth {
            if (status == null) {
                throw new IllegalArgumentException("export status is required");
            }
        }

        public boolean healthy() {
            return clusterConnected && acceptingCommands;
        }
    }
}
