package com.surprising.realtime.api;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;

public record RealtimeRoute(ProductLine productLine, long userId, String channel, String instrumentId) {
    public RealtimeRoute {
        if (productLine == null || userId < 0 || channel == null || instrumentId == null)
            throw new IllegalArgumentException("invalid route");
        if (userId > 0) {
            channel = "USER";
            instrumentId = "";
        }
    }

    public String key() {
        return productLine.name()
                + ":"
                + (userId > 0 ? "user:" + userId : "public:" + channel + ":" + instrumentId);
    }

    public static RealtimeRoute of(RealtimeFrame f) {
        return new RealtimeRoute(f.productLine(), f.userId(), f.kind().name(), f.instrumentId());
    }
}
