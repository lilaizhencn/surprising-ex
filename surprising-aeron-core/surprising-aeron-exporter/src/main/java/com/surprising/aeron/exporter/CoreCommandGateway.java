package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;

@FunctionalInterface
public interface CoreCommandGateway {

    CoreResponse submit(CoreMessage message);
}
