package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
        long delta = Long.parseLong(System.getProperty("surprising.aeron.probe-delta", "1"));
        long sourceId = Long.parseLong(System.getProperty("surprising.aeron.source-id", "0"));
        boolean queryOnly = "query".equalsIgnoreCase(System.getProperty("surprising.aeron.probe-mode", "increment"));
        long now = System.currentTimeMillis();
        UUID commandId = UUID.randomUUID();
        CoreMessageHeader header = queryOnly
                ? CoreMessageHeader.query(CoreMessageType.STATE_HASH_QUERY, commandId, productLine,
                        CommandSource.OPERATIONS, sourceId, now, 0, now, now)
                : CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId, productLine,
                        CommandSource.OPERATIONS, sourceId, now, 0, now, now);
        CoreMessage message = new CoreMessage(header,
                queryOnly ? new byte[0] : CoreProtocol.probePayload(delta));
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hostnames, egressHostname, Duration.ofSeconds(10))) {
            var response = client.submit(message);
            System.out.printf("status=%s appliedCommandCount=%d stateHash=%016x commandId=%s%n",
                    response.status(), response.appliedCommandCount(), response.stateHash(), commandId);
        }
    }
}
