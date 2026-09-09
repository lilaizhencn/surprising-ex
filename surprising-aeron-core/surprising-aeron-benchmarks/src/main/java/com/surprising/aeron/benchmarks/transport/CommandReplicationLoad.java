package com.surprising.aeron.benchmarks.transport;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.agrona.concurrent.UnsafeBuffer;
import org.HdrHistogram.Histogram;

/**
 * 使用生产编码器预构造订单消息，隔离消息复制与业务执行成本。
 * 重用订单内容，仅更新命令标识和序号；不可向生产交易服务发送此工作负载。
 */
public final class CommandReplicationLoad {
    /** 单个发压线程独占窗口、发送时间及确认计数；确认必须连续且不能超过发送数。 */
    private long sent, acknowledged, retries;
    private final long[] started = new long[256];
    private final Histogram latency = new Histogram(60_000_000_000L, 3);

    static byte[] command(int batchSize) {
        var orders = new ArrayList<PlaceOrderCommand>();
        for (int i = 0; i < batchSize; i++) {
            orders.add(new PlaceOrderCommand(i + 1, "BTC-USDT", 1, CoreOrderSide.BUY,
                    100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replication-" + i));
        }
        var type = batchSize == 1 ? CoreMessageType.PLACE_ORDER : CoreMessageType.PLACE_ORDER_BATCH;
        byte[] payload = batchSize == 1 ? TradingCommandCodec.encodePlaceOrder(orders.getFirst())
                : TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders));
        return CoreMessageCodec.encode(CoreMessage.owned(CoreMessageHeader.command(type,
                new UUID(1, 1), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY,
                1, 1, 1000, 1, 1), payload));
    }

    private void phase(AeronCluster cluster, UnsafeBuffer message, int seconds, int batchSize, String name) {
        long initial = sent;
        retries = 0;
        latency.reset();
        long start = System.nanoTime(), end = start + seconds * 1_000_000_000L;
        long keepalive = start;
        long peak = 0;
        do {
            long now = System.nanoTime();
            if (now < end && sent - acknowledged < started.length) {
                long next = sent + 1;
                message.putLong(24, next, ByteOrder.LITTLE_ENDIAN);
                message.putLong(40, next, ByteOrder.LITTLE_ENDIAN);
                message.putLong(64, next, ByteOrder.LITTLE_ENDIAN);
                started[(int) next & 255] = now;
                long result = cluster.offer(message, 0, message.capacity());
                if (result > 0) sent = next;
                else retries++;
                peak = Math.max(peak, sent - acknowledged);
            }
            cluster.pollEgress();
            if (now - keepalive > 500_000_000L) {
                cluster.sendKeepAlive();
                keepalive = now;
            }
            if (now > end + 15_000_000_000L) throw new IllegalStateException("drain deadline");
            Thread.onSpinWait();
        } while (System.nanoTime() < end || acknowledged < sent);
        double elapsed = (System.nanoTime() - start) / 1e9;
        System.out.printf("replication=%s bytes=%d batch=%d elapsed=%.6f offered=%d committedAck=%d "
                        + "messagesPerSec=%.3f representedItemsPerSec=%.3f payloadMiBPerSec=%.3f "
                        + "unfinished=%d peak=%d retries=%d p50us=%.3f p99us=%.3f maxus=%.3f%n",
                name, message.capacity(), batchSize, elapsed, sent - initial, acknowledged - initial,
                (sent - initial) / elapsed, (sent - initial) * batchSize / elapsed,
                (sent - initial) * (double) message.capacity() / elapsed / 1048576,
                sent - acknowledged, peak, retries, latency.getValueAtPercentile(50) / 1e3,
                latency.getValueAtPercentile(99) / 1e3, latency.getMaxValue() / 1e3);
    }

    public static void main(String[] args) {
        int batch = Integer.getInteger("replication.batch", 1);
        if (batch != 1 && batch != 20) throw new IllegalArgumentException("batch must be 1 or 20");
        var load = new CommandReplicationLoad();
        var buffer = new UnsafeBuffer(command(batch));
        try (var driver = MediaDriver.launch(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED)
                    .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var cluster = AeronCluster.connect(new AeronCluster.Context()
                    .aeronDirectoryName(driver.aeronDirectoryName())
                    .messageTimeoutNs(30_000_000_000L)
                    .ingressChannel("aeron:udp")
                    .ingressEndpoints(ProductLineClusterLayout.ingressEndpoints(ProductLine.LINEAR_PERPETUAL,
                            List.of("127.0.0.1", "127.0.0.1", "127.0.0.1")))
                    .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                    .egressListener((session, timestamp, data, offset, length, header) -> {
                        long sequence = data.getLong(offset, ByteOrder.LITTLE_ENDIAN);
                        if (length != 8 || sequence != load.acknowledged + 1 || sequence > load.sent)
                            throw new IllegalStateException("invalid committed acknowledgement");
                        load.acknowledged = sequence;
                        load.latency.recordValue(System.nanoTime() - load.started[(int) sequence & 255]);
                    }))) {
            load.phase(cluster, buffer, 10, batch, "warmup");
            load.phase(cluster, buffer, 30, batch, "measurement");
            System.out.printf("replicationTotal sequence=%d%n", load.acknowledged);
        }
    }
}
