package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.UploadSecurityProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** Bounded ClamAV INSTREAM check before publishing a private upload. Scanner failures reject publication. */
@Service
public class UploadContentScanner implements AutoCloseable {
    private final UploadSecurityProperties properties;
    private final Semaphore scans;
    private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines = new java.util.concurrent.ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("upload-scan-timeout").factory());
    public UploadContentScanner(UploadSecurityProperties properties) {
        this.properties = properties; scans = new Semaphore(properties.getConcurrentScans());
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public void scan(byte[] bytes) {
        if (!properties.isScanEnabled()) return;
        if (!scans.tryAcquire()) throw new GatewayRequestLimits.Rejection(GatewayRequestLimits.Reason.GATEWAY_BUSY, "UPLOAD_SCAN", 1000);
        try (var socket = new Socket()) {
            var deadline = deadlines.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } },
                    properties.getTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(properties.getScannerHost(), properties.getScannerPort()), properties.getTimeoutMillis());
                socket.setSoTimeout(properties.getTimeoutMillis());
                var output = new DataOutputStream(socket.getOutputStream());
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    int length = Math.min(8192, bytes.length - offset);
                    output.writeInt(length); output.write(bytes, offset, length);
                }
                output.writeInt(0); output.flush();
                var result = new ByteArrayOutputStream();
                for (int value; (value = socket.getInputStream().read()) > 0;) {
                    if (result.size() >= 1024) throw new IOException("scanner response too large");
                    result.write(value);
                }
                String verdict = result.toString(StandardCharsets.US_ASCII);
                if (verdict.endsWith(" FOUND")) throw new IllegalArgumentException("unsafe file content");
                if (!verdict.equals("stream: OK")) throw new IOException("scanner did not confirm clean content");
            } finally { deadline.cancel(false); }
        } catch (IOException unavailable) {
            throw new GatewayRequestLimits.Rejection(GatewayRequestLimits.Reason.GATEWAY_BUSY, "UPLOAD_SCAN", 1000);
        } finally { scans.release(); }
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() { deadlines.shutdownNow(); }
}
