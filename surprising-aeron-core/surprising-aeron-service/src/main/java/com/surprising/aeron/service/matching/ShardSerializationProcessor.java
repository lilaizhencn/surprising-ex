package com.surprising.aeron.service.matching;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.journaling.SnapshotDescriptor;
import java.io.IOException;
import java.util.NavigableMap;
import java.util.function.Function;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/** Maps the local module id of an independent synchronous engine onto its global matcher shard id. */
final class ShardSerializationProcessor implements ISerializationProcessor {
    private final ISerializationProcessor delegate;
    private final int shardId;

    ShardSerializationProcessor(ISerializationProcessor delegate, int shardId) {
        if (delegate == null || shardId < 0) throw new IllegalArgumentException("invalid shard serializer");
        this.delegate = delegate;
        this.shardId = shardId;
    }

    private int globalInstance(SerializedModuleType type, int localInstance) {
        if (type == SerializedModuleType.MATCHING_ENGINE_ROUTER) {
            if (localInstance != 0) throw new IllegalStateException("synchronous matcher local module must be zero");
            return shardId;
        }
        return localInstance;
    }

    @Override
    public boolean storeData(long snapshotId, long sequence, long timestampNs, SerializedModuleType type,
                             int instanceId, WriteBytesMarshallable writer) {
        return delegate.storeData(snapshotId, sequence, timestampNs, type,
                globalInstance(type, instanceId), writer);
    }

    @Override
    public <T> T loadData(long snapshotId, SerializedModuleType type, int instanceId,
                          Function<BytesIn, T> reader) {
        return delegate.loadData(snapshotId, type, globalInstance(type, instanceId), reader);
    }

    @Override
    public boolean checkSnapshotExists(long snapshotId, SerializedModuleType type, int instanceId) {
        return delegate.checkSnapshotExists(snapshotId, type, globalInstance(type, instanceId));
    }

    @Override public void writeToJournal(OrderCommand cmd, long dSeq, boolean eob) throws IOException {
        delegate.writeToJournal(cmd, dSeq, eob);
    }
    @Override public void enableJournaling(long afterSeq, ExchangeApi api) {
        delegate.enableJournaling(afterSeq, api);
    }
    @Override public NavigableMap<Long, SnapshotDescriptor> findAllSnapshotPoints() {
        return delegate.findAllSnapshotPoints();
    }
    @Override public void replayJournalStep(long snapshotId, long seqFrom, long seqTo, ExchangeApi api) {
        delegate.replayJournalStep(snapshotId, seqFrom, seqTo, api);
    }
    @Override public long replayJournalFull(InitialStateConfiguration initialState, ExchangeApi api) {
        return delegate.replayJournalFull(initialState, api);
    }
    @Override public void replayJournalFullAndThenEnableJouraling(
            InitialStateConfiguration initialState, ExchangeApi api) {
        delegate.replayJournalFullAndThenEnableJouraling(initialState, api);
    }
}
