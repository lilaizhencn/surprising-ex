package com.surprising.aeron.client;

import com.surprising.product.api.ProductLine;
import io.aeron.driver.MediaDriver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AeronClientTransport implements AutoCloseable {

    private final MediaDriver mediaDriver;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AeronClientTransport(MediaDriver mediaDriver) {
        this.mediaDriver = mediaDriver;
    }

    public static AeronClientTransport launch() {
        return new AeronClientTransport(SurprisingAeronClient.newMediaDriver());
    }

    public SurprisingAeronClient connect(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout) {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client transport is closed");
        }
        return SurprisingAeronClient.connect(
                productLine, hostnames, egressHostname, responseTimeout, mediaDriver);
    }

    MediaDriver mediaDriver() {
        return mediaDriver;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            mediaDriver.close();
        }
    }
}
