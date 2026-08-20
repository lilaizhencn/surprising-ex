package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
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
        try (var client = SurprisingAeronClient.connect(productLine, hosts,
                value("AERON_EGRESS_HOSTNAME", "localhost"), Duration.ofSeconds(10))) {
            for (long userId : userIds) {
                var response = query(client, productLine, CoreMessageType.USER_STATE_QUERY, userId);
                if (response.status() != ResponseStatus.OK) {
                    throw new IllegalStateException("user state query failed user=" + userId
                            + " result=" + response.resultCode());
                }
                var state = CoreStateQueryCodec.decodeUserState(response.data());
                for (var balance : state.balances()) {
                    long total = Math.addExact(balance.availableUnits(), balance.lockedUnits());
                    actual.merge(balance.asset(), total, Math::addExact);
                    System.out.printf("user=%d asset=%s available=%d locked=%d total=%d%n",
                            userId, balance.asset(), balance.availableUnits(), balance.lockedUnits(), total);
                }
                for (var position : state.positions()) {
                    System.out.printf("user=%d position=symbol:%s side:%s quantity=%d entry=%d margin=%d realized=%d%n",
                            userId, position.symbol(), position.positionSide(), position.signedQuantitySteps(),
                            position.entryPriceTicks(), position.positionMarginUnits(), position.realizedPnlUnits());
                }
                var risk = CoreRiskQueryCodec.decode(query(client, productLine,
                        CoreMessageType.RISK_STATE_QUERY, userId).data());
                for (var snapshot : risk) {
                    System.out.printf("risk user=%d symbol=%s unrealized=%d equity=%d status=%s%n",
                            userId, snapshot.symbol(), snapshot.unrealizedPnlUnits(), snapshot.equityUnits(),
                            snapshot.status());
                }
            }
            var treasuryResponse = query(client, productLine, CoreMessageType.TREASURY_STATE_QUERY, 0);
            if (treasuryResponse.status() != ResponseStatus.OK) {
                throw new IllegalStateException("treasury query failed: " + treasuryResponse.resultCode());
            }
            for (var treasury : CoreStateQueryCodec.decodeTreasuryState(treasuryResponse.data())) {
                long economicBalance = Math.subtractExact(
                        Math.addExact(treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()),
                        treasury.insuranceDeficitUnits());
                actual.merge(treasury.asset(), economicBalance, Math::addExact);
                System.out.printf("treasury asset=%s fees=%d insurance=%d deficit=%d economic=%d%n",
                        treasury.asset(), treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits(),
                        treasury.insuranceDeficitUnits(), economicBalance);
            }
            Map<Long, com.surprising.aeron.protocol.CoreLiquidationWorkView.Resolution> outstanding =
                    new LinkedHashMap<>();
            for (var purpose : List.of(
                    com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.INSURANCE,
                    com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.ADL)) {
                var workResponse = query(client, productLine, CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                        CoreLiquidationWorkCodec.encodeQuery(productLine, purpose, 0, 100, 1_048_576));
                if (workResponse.status() != ResponseStatus.OK) {
                    throw new IllegalStateException("liquidation work query failed purpose=" + purpose
                            + " result=" + workResponse.resultCode());
                }
                var liquidationWork = CoreLiquidationWorkCodec.decodeWork(workResponse.data());
                System.out.printf("liquidationWork purpose=%s actions=%d resolutions=%d complete=%s cursor=%d%n",
                        purpose, liquidationWork.actions().size(), liquidationWork.resolutions().size(),
                        liquidationWork.complete(), liquidationWork.nextCursorLiquidationId());
                for (var resolution : liquidationWork.resolutions()) {
                    var previous = outstanding.putIfAbsent(resolution.liquidationId(), resolution);
                    if (previous != null && (previous.deficitUnits() != resolution.deficitUnits()
                            || !previous.asset().equals(resolution.asset()))) {
                        throw new IllegalStateException("liquidation work changed while reconciling id="
                                + resolution.liquidationId());
                    }
                }
            }
            for (var resolution : outstanding.values()) {
                actual.merge(resolution.asset(), resolution.deficitUnits(), Math::addExact);
                System.out.printf("liquidation asset=%s id=%d deficit=%d purpose=%s%n",
                        resolution.asset(), resolution.liquidationId(), resolution.deficitUnits(), resolution.purpose());
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

    private static com.surprising.aeron.protocol.CoreResponse query(
            SurprisingAeronClient client, ProductLine productLine, CoreMessageType type, long userId) {
        return query(client, productLine, type, userId, new byte[0]);
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
