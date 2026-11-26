package com.surprising.aeron.service.export;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.export.CommittedBalanceFlow;
import com.surprising.aeron.protocol.export.CommittedCommandResult;
import com.surprising.aeron.protocol.export.CommittedDeliveryPage;
import com.surprising.aeron.protocol.export.CommittedFundingPage;
import com.surprising.aeron.protocol.export.CommittedTradingActivity;
import com.surprising.aeron.protocol.export.CoreProjectionBatchEvent;
import com.surprising.aeron.protocol.export.LiquidationChange;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.ProductTopicNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * Core Leader 侧异步投影与公开成交导出器。
 * Owner 线程仅做非阻塞入队（never blocks Aeron Cluster），后台单线程批量写入 Kafka：
 * 1. 公开成交 -> {@code surprising-committed-trades-<PRODUCT_LINE>}（供 Candlestick K 线消费）
 * 2. 投影增量批次 -> {@code surprising-core-projection-<PRODUCT_LINE>}（供无状态 Gateway 消费落库）
 */
@Slf4j
public final class CoreProjectionExporter implements AutoCloseable {
    private static final CoreOrderSide[] ORDER_SIDES = CoreOrderSide.values();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 surprising-trading-api 中 PublicTradeEvent JSON 结构完全一致。 */
    private record PublicTradePayload(
            String tradeId,
            long sequence,
            String instrumentId,
            String takerSide,
            long priceTicks,
            long quantitySteps,
            Instant eventTime
    ) {}

    public record ExportItem(
            long exportSequence,
            long clusterPosition,
            long occurredAt,
            List<RealtimeFrame> orderChanges,
            CommittedCommandResult commandResult,
            List<CoreAlgoOrderView> algoChanges,
            CommittedBalanceFlow balanceFlow,
            List<LiquidationChange> liquidationChanges,
            List<CoreTradeFeeObservation> feeObservations,
            CommittedFundingPage fundingPage,
            CommittedDeliveryPage deliveryPage,
            CommittedTradingActivity activity
    ) {}

    private final ProductLine productLine;
    private final boolean enabled;
    private final String bootstrapServers;
    private final long batchMaxDelayNanos;
    private final int batchMaxRecords;
    private final ArrayBlockingQueue<ExportItem> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private volatile long tradeSequence = 0L;
    private volatile long lastDropLogNanos = 0L;
    private Thread worker;

    public CoreProjectionExporter(ProductLine productLine, CoreProjectionExportConfiguration config) {
        this.productLine = java.util.Objects.requireNonNull(productLine, "productLine");
        this.enabled = config != null && config.isEnabled();
        String servers = config == null ? "" : config.getBootstrapServers();
        this.bootstrapServers = servers == null ? "" : servers.trim();
        int capacity = config == null ? 16384 : Math.max(256, config.getQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchMaxDelayNanos = TimeUnit.MILLISECONDS.toNanos(
                config == null ? 5 : Math.max(1, config.getBatchMaxDelayMillis()));
        this.batchMaxRecords = config == null ? 256 : Math.max(1, config.getBatchMaxRecords());
    }

    public boolean enabled() {
        return enabled && !bootstrapServers.isEmpty();
    }

    public void setLeader(boolean isLeader) {
        leader.set(isLeader);
    }

    public boolean isLeader() {
        return leader.get();
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public synchronized void start() {
        if (!enabled() || running.get()) return;
        running.set(true);
        worker = new Thread(this::runLoop, "core-projection-exporter-" + productLine.name().toLowerCase());
        worker.setDaemon(true);
        worker.start();
        log.info("CoreProjectionExporter started productLine={} bootstrapServers={} tradesTopic={} projectionTopic={}",
                productLine, bootstrapServers,
                ProductTopicNames.of(productLine).matchTradesTopic(),
                CoreProjectionBatchEvent.projectionTopic(productLine));
    }

    /** Owner 线程非阻塞入队；队列满时丢弃并限频告警，绝不阻塞 Cluster 线程。 */
    public void offer(ExportItem item) {
        if (!enabled() || !leader.get() || item == null) return;
        if (!queue.offer(item)) {
            long dropped = droppedCount.incrementAndGet();
            long now = System.nanoTime();
            if (now - lastDropLogNanos >= 5_000_000_000L) {
                lastDropLogNanos = now;
                log.warn("CoreProjectionExporter queue full; dropped={} productLine={} clusterPosition={}",
                        dropped, productLine, item.clusterPosition());
            }
        }
    }

    private void runLoop() {
        String tradesTopic = ProductTopicNames.of(productLine).matchTradesTopic();
        String projectionTopic = CoreProjectionBatchEvent.projectionTopic(productLine);
        while (running.get()) {
            try (Producer<String, String> producer = createProducer()) {
                drainLoop(producer, tradesTopic, projectionTopic);
            } catch (Exception ex) {
                if (running.get()) {
                    log.error("CoreProjectionExporter Kafka error productLine={}; retrying in 1s", productLine, ex);
                    sleepQuietly(1000L);
                }
            }
        }
    }

    private Producer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "2");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        return new KafkaProducer<>(props);
    }

    private void drainLoop(Producer<String, String> producer, String tradesTopic, String projectionTopic)
            throws InterruptedException {
        var batch = new ArrayList<ExportItem>(batchMaxRecords);
        while (running.get()) {
            ExportItem first = queue.poll(100, TimeUnit.MILLISECONDS);
            if (first == null) continue;
            batch.clear();
            batch.add(first);
            long deadline = System.nanoTime() + batchMaxDelayNanos;
            while (batch.size() < batchMaxRecords) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                ExportItem next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                batch.add(next);
            }
            flushBatch(producer, tradesTopic, projectionTopic, batch);
        }
        // Drain remaining items on shutdown
        batch.clear();
        queue.drainTo(batch, batchMaxRecords);
        while (!batch.isEmpty()) {
            flushBatch(producer, tradesTopic, projectionTopic, batch);
            batch.clear();
            queue.drainTo(batch, batchMaxRecords);
        }
    }

    private void flushBatch(Producer<String, String> producer, String tradesTopic, String projectionTopic,
            List<ExportItem> items) {
        if (items.isEmpty()) return;
        var orderChanges = new ArrayList<RealtimeFrame>();
        var commandResults = new ArrayList<CommittedCommandResult>();
        var algoChanges = new ArrayList<CoreAlgoOrderView>();
        var balanceFlows = new ArrayList<CommittedBalanceFlow>();
        var liquidationChanges = new ArrayList<LiquidationChange>();
        var feeObservations = new ArrayList<CoreTradeFeeObservation>();
        var fundingPages = new ArrayList<CommittedFundingPage>();
        var deliveryPages = new ArrayList<CommittedDeliveryPage>();
        CommittedTradingActivity latestActivity = null;
        long maxExportSeq = 0;
        long maxClusterPos = 0;
        long maxOccurredAt = 0;

        for (var item : items) {
            maxExportSeq = Math.max(maxExportSeq, item.exportSequence());
            maxClusterPos = Math.max(maxClusterPos, item.clusterPosition());
            maxOccurredAt = Math.max(maxOccurredAt, item.occurredAt());
            if (item.orderChanges() != null) {
                for (var frame : item.orderChanges()) {
                    if (frame.kind() == RealtimeFrame.Kind.TRADE && frame.payloadLength() == 25) {
                        sendPublicTrade(producer, tradesTopic, frame);
                    }
                    orderChanges.add(frame);
                }
            }
            if (item.commandResult() != null) commandResults.add(item.commandResult());
            if (item.algoChanges() != null) algoChanges.addAll(item.algoChanges());
            if (item.balanceFlow() != null) balanceFlows.add(item.balanceFlow());
            if (item.liquidationChanges() != null) liquidationChanges.addAll(item.liquidationChanges());
            if (item.feeObservations() != null) feeObservations.addAll(item.feeObservations());
            if (item.fundingPage() != null) fundingPages.add(item.fundingPage());
            if (item.deliveryPage() != null) deliveryPages.add(item.deliveryPage());
            if (item.activity() != null) latestActivity = item.activity();
        }

        CoreProjectionBatchEvent.StatisticsEvent statsEvent = latestActivity == null ? null
                : new CoreProjectionBatchEvent.StatisticsEvent(latestActivity, maxClusterPos, maxExportSeq, maxOccurredAt);

        if (!orderChanges.isEmpty() || !commandResults.isEmpty() || !algoChanges.isEmpty()
                || !balanceFlows.isEmpty() || !liquidationChanges.isEmpty() || !feeObservations.isEmpty()
                || !fundingPages.isEmpty() || !deliveryPages.isEmpty() || statsEvent != null) {
            var event = new CoreProjectionBatchEvent(
                    productLine,
                    maxExportSeq,
                    maxClusterPos,
                    maxOccurredAt,
                    orderChanges,
                    commandResults,
                    algoChanges,
                    balanceFlows,
                    liquidationChanges,
                    feeObservations,
                    fundingPages,
                    deliveryPages,
                    List.of(),
                    statsEvent);
            try {
                String json = MAPPER.writeValueAsString(event);
                producer.send(new ProducerRecord<>(projectionTopic, productLine.name(), json));
            } catch (Exception ex) {
                log.error("Failed to serialize/send CoreProjectionBatchEvent productLine={} pos={}",
                        productLine, maxClusterPos, ex);
            }
        }
        producer.flush();
    }

    private void sendPublicTrade(Producer<String, String> producer, String tradesTopic, RealtimeFrame frame) {
        var b = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
        long price = b.getLong();
        long quantity = b.getLong();
        b.getLong();
        int side = b.get();
        var payload = new PublicTradePayload(
                frame.entityId(),
                ++tradeSequence,
                frame.instrumentId(),
                ORDER_SIDES[side].name(),
                price,
                quantity,
                Instant.ofEpochMilli(frame.timestamp()));
        try {
            String json = MAPPER.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(tradesTopic, frame.instrumentId(), json));
        } catch (Exception ex) {
            log.error("Failed to serialize/send PublicTradePayload productLine={} tradeId={}",
                    productLine, frame.entityId(), ex);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }
}
