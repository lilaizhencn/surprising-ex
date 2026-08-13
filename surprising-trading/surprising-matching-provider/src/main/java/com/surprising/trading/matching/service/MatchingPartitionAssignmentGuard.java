package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.store.MatchingLocalStateStore;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.stereotype.Service;

@Service
public class MatchingPartitionAssignmentGuard implements ConsumerAwareRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(MatchingPartitionAssignmentGuard.class);

    private final MatchingProperties properties;
    private final ConfigurableApplicationContext applicationContext;
    private final MatchingLocalStateStore localStateStore;
    private final Set<TopicPartition> activePartitions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean processedCommands = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private final AtomicLong assignmentEpoch = new AtomicLong();
    private final long startedAtMs = System.currentTimeMillis();

    public MatchingPartitionAssignmentGuard(MatchingProperties properties,
                                            ConfigurableApplicationContext applicationContext) {
        this(properties, applicationContext, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MatchingPartitionAssignmentGuard(MatchingProperties properties,
                                            ConfigurableApplicationContext applicationContext,
                                            MatchingLocalStateStore localStateStore) {
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.localStateStore = localStateStore;
        this.assignmentEpoch.set(localStateStore == null ? 0L : localStateStore.assignmentEpoch());
    }

    public void recordProcessedCommand(String topic, int partition) {
        processedCommands.set(true);
        activePartitions.add(new TopicPartition(topic, partition));
    }

    public long currentEpoch() {
        return assignmentEpoch.get();
    }

    public void requireEpoch(long expectedEpoch) {
        long actualEpoch = assignmentEpoch.get();
        if (actualEpoch != expectedEpoch) {
            throw new IllegalStateException("matching partition assignment epoch changed during command batch: expected="
                    + expectedEpoch + " actual=" + actualEpoch);
        }
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return;
        }
        List<TopicPartition> newlyAssigned = partitions.stream()
                .filter(partition -> !activePartitions.contains(partition))
                .toList();
        activePartitions.addAll(partitions);
        advanceEpoch();
        log.info("Matching Kafka partitions assigned partitions={}", partitions);
        if (!newlyAssigned.isEmpty() && processedCommands.get() && !withinStartupGrace()) {
            requestPartitionReassignmentRestart("new Kafka partitions assigned after this matcher already processed commands: "
                    + newlyAssigned);
        }
    }

    @Override
    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return;
        }
        activePartitions.removeAll(partitions);
        advanceEpoch();
        log.warn("Matching Kafka partitions revoked partitions={}", partitions);
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return;
        }
        activePartitions.removeAll(partitions);
        advanceEpoch();
        requestPartitionReassignmentRestart("Kafka partitions were lost by this matcher: " + partitions);
    }

    private void requestPartitionReassignmentRestart(String reason) {
        if (!properties.getKafka().isRestartOnPartitionReassignment()) {
            log.warn("Matching partition reassignment guard disabled: {}", reason);
            return;
        }
        requestRestart(reason);
    }

    public void requestRestart(String reason) {
        if (shutdownStarted.compareAndSet(false, true)) {
            // exchange-core 无法在运行中的 JVM 内安全热重建单个过期交易对订单簿。
            log.error("{}; closing Spring context so orchestration restarts with a fresh DB order-book recovery",
                    reason);
            Thread shutdownThread = new Thread(applicationContext::close, "matching-rebalance-shutdown");
            shutdownThread.setDaemon(false);
            shutdownThread.start();
        }
    }

    private boolean withinStartupGrace() {
        long graceMs = Math.max(0L, properties.getKafka().getPartitionAssignmentStartupGraceMs());
        return System.currentTimeMillis() - startedAtMs <= graceMs;
    }

    private void advanceEpoch() {
        long next = localStateStore == null
                ? assignmentEpoch.incrementAndGet()
                : localStateStore.advanceAssignmentEpoch();
        assignmentEpoch.set(next);
    }
}
