package com.surprising.realtime.provider.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("surprising.trade-export")
public record TradeExportProperties(
        Path clusterDirectory, String aeronDirectory, String archiveControlChannel,
        Path checkpoint, Integer clusterId, String kafkaConfig,
        Duration checkpointInterval, Duration retryInterval) {
    public TradeExportProperties {
        if (clusterDirectory == null || !Files.isDirectory(clusterDirectory))
            throw new IllegalArgumentException("trade-export cluster-directory must exist");
        if (aeronDirectory == null || aeronDirectory.isBlank())
            throw new IllegalArgumentException("trade-export aeron-directory is required");
        if (archiveControlChannel == null || archiveControlChannel.isBlank())
            throw new IllegalArgumentException("trade-export archive-control-channel is required");
        if (checkpoint == null || checkpoint.toString().isBlank())
            throw new IllegalArgumentException("trade-export checkpoint is required");
        if (clusterId != null && clusterId < 0) throw new IllegalArgumentException("trade-export cluster-id must be nonnegative");
        checkpointInterval = checkpointInterval == null ? Duration.ofSeconds(60) : checkpointInterval;
        retryInterval = retryInterval == null ? Duration.ofSeconds(5) : retryInterval;
        if (checkpointInterval.compareTo(Duration.ofSeconds(1)) < 0 || retryInterval.toMillis() < 100)
            throw new IllegalArgumentException("trade-export checkpoint >= 1s and retry >= 100ms required");
    }
}
