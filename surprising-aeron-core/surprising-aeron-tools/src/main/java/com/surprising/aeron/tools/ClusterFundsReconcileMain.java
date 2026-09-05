package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ClusterFundsReconcileMain {

    private ClusterFundsReconcileMain() {
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(required("PRODUCT_LINE"));
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("funds reconciliation requires LINEAR_PERPETUAL");
        }
        var users = FundsReconciliation.UserRanges.parse(required("RECONCILE_USER_RANGES"));
        var makers = FundsReconciliation.UserRanges.parse(value("RECONCILE_MAKER_RANGES", ""));
        var ledger = FundsReconciliation.Ledger.read(Path.of(required("RECONCILE_LEDGER")));
        int pageSize = positiveInt("RECONCILE_LIQUIDATION_PAGE_SIZE", 100, 1_000);
        int maxPages = positiveInt("RECONCILE_MAX_LIQUIDATION_PAGES", 100_000, Integer.MAX_VALUE);
        String checkpointValue = value("RECONCILE_CHECKPOINT", "");
        Path checkpoint = checkpointValue.isEmpty() ? null : Path.of(checkpointValue);
        var config = new FundsReconciliation.Config(productLine, users, makers, ledger,
                pageSize, maxPages, checkpoint);
        List<String> hosts = Arrays.stream(value("AERON_HOSTNAMES", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(host -> !host.isEmpty()).toList();
        if (hosts.isEmpty()) throw new IllegalArgumentException("AERON_HOSTNAMES must contain a host");

        FundsReconciliation.Result result;
        try (var client = SurprisingAeronClient.connect(productLine, hosts,
                value("AERON_EGRESS_HOSTNAME", "localhost"), Duration.ofSeconds(10))) {
            result = FundsReconciliation.reconcile(config,
                    (type, userId, payload) -> query(client, productLine, type, userId, payload));
        }
        System.out.printf("fundsReconcile=PASS productLine=%s users=%d makers=%d treasury=1 "
                        + "liquidationPages=%d fundsDiff=%d coreStateHash=%d stateHash=%s fundsHash=%s assets=%s%n",
                productLine, result.userCount(), result.makerCount(), result.liquidationPages(),
                result.fundsDifference(), result.coreStateHash(), result.stateHash(), result.fundsHash(),
                result.assets());
    }

    private static com.surprising.aeron.protocol.CoreResponse query(
            SurprisingAeronClient client, ProductLine productLine, CoreMessageType type, long userId,
            byte[] payload) {
        long correlation = System.nanoTime();
        CoreMessage message = new CoreMessage(CoreMessageHeader.query(
                type, UUID.randomUUID(), productLine, CommandSource.OPERATIONS,
                0x46554E4453524543L, correlation, userId, correlation, correlation), payload);
        return client.submit(message);
    }

    private static int positiveInt(String name, int defaultValue, int maximum) {
        String configured = value(name, Integer.toString(defaultValue));
        int parsed;
        try {
            parsed = Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static String required(String name) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException(name + " is required");
        return configured.trim();
    }

    private static String value(String name, String defaultValue) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? defaultValue : configured.trim();
    }
}
