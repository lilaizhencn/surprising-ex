package com.surprising.aeron.exporter;

import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportBatch;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
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
    private final ExporterMetrics metrics;
    private final UUID queryEpoch = UUID.randomUUID();
    private final AtomicLong correlations = new AtomicLong();

    public ReliableCoreExporter(
            ProductLine productLine,
            CoreCommandGateway core,
            CoreExportSink sink,
            int batchSize) {
        this(productLine, core, sink, batchSize, new ExporterMetrics(productLine));
    }

    public ReliableCoreExporter(
            ProductLine productLine,
            CoreCommandGateway core,
            CoreExportSink sink,
            int batchSize,
            ExporterMetrics metrics) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.core = Objects.requireNonNull(core, "core");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        CoreExportCodec.encodeBatchQuery(batchSize);
        this.batchSize = batchSize;
    }

    public ExportCycleResult exportOnce() throws Exception {
        return exportOnce(null);
    }

    private ExportCycleResult exportOnce(CoreExportStatus before) throws Exception {
        try {
            CoreResponse batchResponse = submitQuery(CoreMessageType.EXPORT_BATCH_QUERY,
                    CoreExportCodec.encodeBatchQuery(batchSize));
            requireOk(batchResponse, "export batch query");
            CoreExportBatch batch = CoreExportCodec.decodeBatchResponse(batchResponse.data());
            CoreExportStatus queryStatus = batch.status();
            List<CoreMessage> events = batch.events();
            metrics.observeBatch(queryStatus, events);
            if (before != null && queryStatus.acknowledgedSequence() != before.acknowledgedSequence()) {
                throw new IllegalStateException("export acknowledgement moved during batch query");
            }
            if (events.isEmpty()) {
                if (queryStatus.pendingCount() > 0) {
                    throw new IllegalStateException("non-empty export backlog returned an empty batch");
                }
                return new ExportCycleResult(0, queryStatus);
            }
            validateContiguous(events, queryStatus.acknowledgedSequence() + 1);
            sink.publish(productLine, events);
            long throughSequence = CoreExportCodec.decodeEvent(events.getLast().payload()).exportSequence();
            metrics.recordPublished(events.size(), throughSequence);
            CoreResponse ackResponse = core.submit(ack(throughSequence));
            if (ackResponse.commandStatus() != ResponseStatus.APPLIED
                    && ackResponse.commandStatus() != ResponseStatus.DUPLICATE) {
                throw new IllegalStateException("export ack rejected: " + ackResponse.resultCode());
            }
            if (ackResponse.commandStatus() == ResponseStatus.DUPLICATE) {
                metrics.recordDuplicate(1);
            }
            CoreExportStatus after = ackResponse.data().length == 0
                    ? statusAfterAck(queryStatus, events) : CoreExportCodec.decodeStatus(ackResponse.data());
            metrics.recordAcknowledged(after);
            return new ExportCycleResult(events.size(), after);
        } catch (ResultUnknownException exception) {
            metrics.recordUnknown();
            metrics.recordRetry();
            throw exception;
        } catch (Exception exception) {
            metrics.recordFailure();
            metrics.recordRetry();
            throw exception;
        }
    }

    public CoreExportStatus status() {
        CoreResponse response = submitQuery(CoreMessageType.EXPORT_STATUS_QUERY, new byte[0]);
        requireOk(response, "export status query");
        CoreExportStatus status = CoreExportCodec.decodeStatus(response.data());
        metrics.recordAcknowledged(status);
        return status;
    }

    public ExportHealth health() {
        CoreExportStatus current = status();
        return new ExportHealth(true, current.acceptingCommands(), current);
    }

    public CoreExportStatus drain(int maxCycles) throws Exception {
        if (maxCycles <= 0) {
            throw new IllegalArgumentException("maxCycles must be positive");
        }
        CoreExportStatus current = null;
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            current = exportOnce(current).status();
            if (current.pendingCount() == 0) {
                return current;
            }
        }
        if (current != null && current.pendingCount() > 0) {
            throw new IllegalStateException("export drain exceeded max cycles; pending=" + current.pendingCount());
        }
        return current;
    }

    public ExporterMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    private CoreResponse submitQuery(CoreMessageType type, byte[] payload) {
        metrics.recordQuery();
        return core.submit(query(type, payload));
    }

    private CoreMessage query(CoreMessageType type, byte[] payload) {
        long correlation = correlations.incrementAndGet();
        UUID queryId = UUID.nameUUIDFromBytes((productLine + ":query:" + queryEpoch + ':' + correlation)
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

    private static CoreExportStatus statusAfterAck(CoreExportStatus before, List<CoreMessage> events) {
        long encodedBytes = events.stream()
                .mapToLong(message -> CoreProtocol.HEADER_LENGTH + message.payloadLength()).sum();
        return new CoreExportStatus(
                CoreExportCodec.decodeEvent(events.getLast().payload()).exportSequence(),
                before.nextSequence(),
                Math.max(0, before.pendingCount() - events.size()),
                Math.max(0, before.pendingBytes() - encodedBytes),
                before.maxPendingCount(), before.maxPendingBytes());
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
