package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClusterFundsReconcileMain {

    private ClusterFundsReconcileMain() {
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(required("PRODUCT_LINE"));
        List<Long> userIds = parseUsers(required("RECONCILE_USER_RANGES"));
        Map<String, Long> expected = parseTotals(required("RECONCILE_ASSET_TOTALS"));
        List<String> hosts = Arrays.stream(value("AERON_HOSTNAMES", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(host -> !host.isEmpty()).toList();
        Map<String, Long> actual = new LinkedHashMap<>();
        try (var clients = new AeronClientPool("funds-reconcile", productLine, hosts,
                value("AERON_EGRESS_HOSTNAME", "localhost"), Duration.ofSeconds(10), 16)) {
            for (long userId : userIds) {
                var response = clients.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
                if (response.status() != ResponseStatus.OK) {
                    throw new IllegalStateException("user state query failed user=" + userId
                            + " result=" + response.resultCode());
                }
                var state = CoreStateQueryCodec.decodeUserState(response.data());
                for (var balance : state.balances()) {
                    actual.merge(balance.asset(), Math.addExact(balance.availableUnits(), balance.lockedUnits()),
                            Math::addExact);
                }
            }
            var treasuryResponse = clients.query(CoreMessageType.TREASURY_STATE_QUERY, UUID.randomUUID(), 0,
                    new byte[0]);
            if (treasuryResponse.status() != ResponseStatus.OK) {
                throw new IllegalStateException("treasury query failed: " + treasuryResponse.resultCode());
            }
            for (var treasury : CoreStateQueryCodec.decodeTreasuryState(treasuryResponse.data())) {
                long economicBalance = Math.subtractExact(
                        Math.addExact(treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()),
                        treasury.insuranceDeficitUnits());
                actual.merge(treasury.asset(), economicBalance, Math::addExact);
            }
        }
        for (var entry : expected.entrySet()) {
            long value = actual.getOrDefault(entry.getKey(), 0L);
            long difference = Math.subtractExact(value, entry.getValue());
            System.out.printf("asset=%s expected=%d actual=%d difference=%d%n",
                    entry.getKey(), entry.getValue(), value, difference);
            if (difference != 0) {
                throw new IllegalStateException("funds mismatch asset=" + entry.getKey());
            }
        }
        System.out.printf("fundsReconcile=PASS productLine=%s users=%d fundsDiff=0%n",
                productLine, userIds.size());
    }

    private static List<Long> parseUsers(String configured) {
        List<Long> users = new ArrayList<>();
        for (String range : configured.split(",")) {
            String[] bounds = range.trim().split(":", -1);
            if (bounds.length != 2) throw new IllegalArgumentException("invalid user range: " + range);
            long start = Long.parseLong(bounds[0]);
            long endExclusive = Long.parseLong(bounds[1]);
            if (start <= 0 || endExclusive <= start) throw new IllegalArgumentException("invalid user range: " + range);
            for (long userId = start; userId < endExclusive; userId = Math.incrementExact(userId)) {
                users.add(userId);
            }
        }
        return List.copyOf(users);
    }

    private static Map<String, Long> parseTotals(String configured) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String item : configured.split(",")) {
            String[] fields = item.trim().split(":", -1);
            if (fields.length != 2 || fields[0].isBlank()) {
                throw new IllegalArgumentException("invalid asset total: " + item);
            }
            totals.put(fields[0].trim().toUpperCase(), Long.parseLong(fields[1]));
        }
        return Map.copyOf(totals);
    }

    private static String required(String name) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException(name + " is required");
        return configured.trim();
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }
}
