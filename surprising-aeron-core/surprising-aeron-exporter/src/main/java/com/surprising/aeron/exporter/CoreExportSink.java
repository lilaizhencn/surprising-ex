package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreMessage;

@FunctionalInterface
public interface CoreExportSink {

    void publish(long exportSequence, CoreMessage exportEvent);
}
