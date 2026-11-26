package com.surprising.aeron.service.export;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Core 异步投影导出配置（Leader 线程无阻塞入队，后台线程批量写 Kafka）。 */
@Component
@ConfigurationProperties("surprising.aeron.export")
public class CoreProjectionExportConfiguration {
    private boolean enabled = false;
    private String bootstrapServers = "";
    private int queueCapacity = 16384;
    private long batchMaxDelayMillis = 5;
    private int batchMaxRecords = 256;
    private long activityIntervalMillis = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public long getBatchMaxDelayMillis() { return batchMaxDelayMillis; }
    public void setBatchMaxDelayMillis(long batchMaxDelayMillis) { this.batchMaxDelayMillis = batchMaxDelayMillis; }

    public int getBatchMaxRecords() { return batchMaxRecords; }
    public void setBatchMaxRecords(int batchMaxRecords) { this.batchMaxRecords = batchMaxRecords; }

    public long getActivityIntervalMillis() { return activityIntervalMillis; }
    public void setActivityIntervalMillis(long activityIntervalMillis) { this.activityIntervalMillis = activityIntervalMillis; }
}
