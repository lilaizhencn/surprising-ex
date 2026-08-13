package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.product.api.ProductLine;
import java.util.List;

@FunctionalInterface
public interface CoreExportSink {

    void publish(ProductLine productLine, List<CoreMessage> events) throws Exception;
}
