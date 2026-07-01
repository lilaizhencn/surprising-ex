package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Set<TopicPartition> activePartitions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean processedCommands = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private final long startedAtMs = System.currentTimeMillis();

    public MatchingPartitionAssignmentGuard(MatchingProperties properties,
                                            ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    public void recordProcessedCommand(String topic, int partition) {
        processedCommands.set(true);
        activePartitions.add(new TopicPartition(topic, partition));
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
        log.warn("Matching Kafka partitions revoked partitions={}", partitions);
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return;
        }
        activePartitions.removeAll(partitions);
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
            // exchange-core cannot safely hot-rebuild one stale symbol book inside a running JVM.
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
}
