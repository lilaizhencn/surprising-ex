package com.surprising.aeron.tools.diagnostics;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreLaneMetricsCodec;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
public final class ClusterProbeMain {

    private ClusterProbeMain() {
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        List<String> hostnames = Arrays.stream(System.getProperty(
                "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim)
                .toList();
        String egressHostname = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long sourceId = Long.parseLong(System.getProperty("surprising.aeron.source-id", "0"));
        String mode = System.getProperty("surprising.aeron.probe-mode", "query");
        boolean queryOnly = "query".equalsIgnoreCase(mode);
        boolean metricsOnly = "metrics".equalsIgnoreCase(mode);
        if (!queryOnly && !metricsOnly) throw new IllegalArgumentException("probe-mode must be query or metrics");
        long now = System.currentTimeMillis();
        UUID commandId = UUID.randomUUID();
        CoreMessageHeader header = CoreMessageHeader.query(metricsOnly ? CoreMessageType.LANE_METRICS_QUERY
                        : CoreMessageType.STATE_HASH_QUERY, commandId, productLine,
                CommandSource.OPERATIONS, sourceId, now, 0, now, now);
        CoreMessage message = new CoreMessage(header, CoreMessage.EMPTY_PAYLOAD);
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hostnames, egressHostname, Duration.ofSeconds(10))) {
            var response = client.submit(message);
            if (metricsOnly) {
                log.info("{}", CoreLaneMetricsPrometheusFormatter.format(productLine,
                        CoreLaneMetricsCodec.decode(response.data())).stripTrailing());
                return;
            }
            long stateHash = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeStateHash(response.data());
            log.info("{}", String.format(java.util.Locale.ROOT, "status=%s appliedCommandCount=%d stateHash=%016x commandId=%s%n",
                    response.status(), response.appliedCommandCount(), stateHash, commandId).stripTrailing());
        }
    }
}
