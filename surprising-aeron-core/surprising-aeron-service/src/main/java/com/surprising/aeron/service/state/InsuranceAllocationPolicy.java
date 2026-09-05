package com.surprising.aeron.service.state;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Deterministic pro-rata allocation for the shared insurance balance of one settlement asset. */
public final class InsuranceAllocationPolicy {

    private static final Comparator<Claim> CLAIM_ORDER = Comparator
            .comparingLong((Claim claim) -> claim.liquidation().triggerPriceSequence())
            .thenComparingLong(claim -> claim.liquidation().userId())
            .thenComparing(Claim::symbol)
            .thenComparingInt(claim -> claim.liquidation().positionSide().ordinal())
            .thenComparingLong(claim -> claim.liquidation().liquidationId());
    private static final Comparator<CoreClaim> CORE_CLAIM_ORDER = Comparator
            .comparingLong((CoreClaim claim) -> claim.liquidation().triggerPriceSequence())
            .thenComparingLong(claim -> claim.liquidation().userId())
            .thenComparing(claim -> claim.liquidation().symbol())
            .thenComparingInt(claim -> claim.liquidation().positionSide().ordinal())
            .thenComparingLong(claim -> claim.liquidation().liquidationId());

    private InsuranceAllocationPolicy() {
    }

    public static Map<Long, Long> allocations(TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities,
                                               Iterable<Long> candidateIds) {
        if (runtime == null || identities == null || candidateIds == null) {
            throw new IllegalArgumentException("insurance allocation inputs are required");
        }
        Map<Integer, ArrayList<Claim>> byAsset = new HashMap<>();
        for (Long candidateId : candidateIds) {
            if (candidateId == null) continue;
            LiquidationRuntime liquidation = runtime.liquidation(candidateId);
            if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                continue;
            }
            CoreInstrumentState instrument = runtime.instrument(identities.symbol(liquidation.symbolId()));
            if (instrument == null || instrument.version() != liquidation.instrumentVersion()) continue;
            int assetId = identities.assetId(instrument.settleAsset());
            byAsset.computeIfAbsent(assetId, ignored -> new ArrayList<>())
                    .add(new Claim(liquidation, identities.symbol(liquidation.symbolId())));
        }
        Map<Long, Long> result = new HashMap<>();
        byAsset.forEach((assetId, claims) -> allocateAsset(runtime.treasury().insurance(assetId), claims, result));
        return result;
    }

    public static long expectedCoverage(TradingRuntimeState runtime,
                                        RuntimeIdentityRegistry identities,
                                        Iterable<Long> candidateIds,
                                        long liquidationId) {
        if (runtime == null || identities == null || candidateIds == null) {
            throw new IllegalArgumentException("insurance allocation inputs are required");
        }
        LiquidationRuntime target = runtime.liquidation(liquidationId);
        if (target == null || target.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) return 0;
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(target.symbolId()));
        if (instrument == null || instrument.version() != target.instrumentVersion()) return 0;
        int assetId = identities.assetId(instrument.settleAsset());
        BigInteger total = BigInteger.ZERO;
        int rank = 0;
        boolean found = false;
        for (Long id : candidateIds) {
            LiquidationRuntime claim = eligible(runtime, identities, id, assetId);
            if (claim == null) continue;
            total = total.add(BigInteger.valueOf(claim.deficitUnits()));
            if (compare(claim, target, identities) < 0) rank++;
            if (claim.liquidationId() == liquidationId) found = true;
        }
        if (!found || total.signum() == 0) return 0;
        BigInteger available = BigInteger.valueOf(Math.max(0, runtime.treasury().insurance(assetId))).min(total);
        if (available.signum() == 0) return 0;
        if (available.equals(total)) return target.deficitUnits();
        BigInteger allocated = BigInteger.ZERO;
        long targetBase = 0;
        for (Long id : candidateIds) {
            LiquidationRuntime claim = eligible(runtime, identities, id, assetId);
            if (claim == null) continue;
            BigInteger share = available.multiply(BigInteger.valueOf(claim.deficitUnits())).divide(total);
            allocated = allocated.add(share);
            if (claim.liquidationId() == liquidationId) targetBase = share.longValueExact();
        }
        // The remainder goes to the same deterministic prefix as allocations(), without sorting
        // or constructing a result map for unrelated assets. Recompute after each settled claim.
        return targetBase + (rank < available.subtract(allocated).intValueExact() ? 1 : 0);
    }

    private static LiquidationRuntime eligible(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                               Long id, int assetId) {
        if (id == null) return null;
        LiquidationRuntime claim = runtime.liquidation(id);
        if (claim == null || claim.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) return null;
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(claim.symbolId()));
        return instrument != null && instrument.version() == claim.instrumentVersion()
                && identities.assetId(instrument.settleAsset()) == assetId ? claim : null;
    }

    private static int compare(LiquidationRuntime left, LiquidationRuntime right,
                               RuntimeIdentityRegistry identities) {
        int order = Long.compare(left.triggerPriceSequence(), right.triggerPriceSequence());
        if (order == 0) order = Long.compare(left.userId(), right.userId());
        if (order == 0) order = identities.symbol(left.symbolId()).compareTo(identities.symbol(right.symbolId()));
        if (order == 0) order = Integer.compare(left.positionSide().ordinal(), right.positionSide().ordinal());
        return order == 0 ? Long.compare(left.liquidationId(), right.liquidationId()) : order;
    }

    public static boolean isNext(TradingRuntimeState runtime,
                                 RuntimeIdentityRegistry identities,
                                 Iterable<Long> candidateIds,
                                 long liquidationId) {
        LiquidationRuntime target = runtime.liquidation(liquidationId);
        if (target == null) return false;
        CoreInstrumentState targetInstrument = runtime.instrument(identities.symbol(target.symbolId()));
        if (targetInstrument == null) return false;
        int targetAssetId = identities.assetId(targetInstrument.settleAsset());
        LiquidationRuntime first = null;
        for (Long candidateId : candidateIds) {
            if (candidateId == null) continue;
            LiquidationRuntime liquidation = runtime.liquidation(candidateId);
            if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                continue;
            }
            CoreInstrumentState instrument = runtime.instrument(identities.symbol(liquidation.symbolId()));
            if (instrument == null || instrument.version() != liquidation.instrumentVersion()) continue;
            if (identities.assetId(instrument.settleAsset()) != targetAssetId) continue;
            if (first == null || compare(liquidation, first, identities) < 0) first = liquidation;
        }
        return first != null && first.liquidationId() == liquidationId;
    }

    public static long expectedCoverage(TradingCoreState state, long liquidationId) {
        if (state == null || liquidationId <= 0) {
            throw new IllegalArgumentException("insurance allocation state is required");
        }
        CoreLiquidationState target = state.riskState().liquidations().get(liquidationId);
        if (target == null || target.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) return 0;
        CoreInstrumentState targetInstrument = state.instruments().get(target.symbol());
        if (targetInstrument == null) return 0;
        String asset = targetInstrument.settleAsset();
        ArrayList<CoreClaim> claims = new ArrayList<>();
        for (CoreLiquidationState liquidation : state.riskState().liquidations().values()) {
            if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) continue;
            CoreInstrumentState instrument = state.instruments().get(liquidation.symbol());
            if (instrument != null && instrument.version() == liquidation.instrumentVersion()
                    && asset.equals(instrument.settleAsset())) {
                claims.add(new CoreClaim(liquidation));
            }
        }
        claims.sort(CORE_CLAIM_ORDER);
        long available = state.treasuryState().insuranceBalances().getOrDefault(asset, 0L);
        return allocationForCore(Math.max(0, available), claims, liquidationId);
    }

    public static boolean isNext(TradingCoreState state, long liquidationId) {
        CoreLiquidationState target = state.riskState().liquidations().get(liquidationId);
        if (target == null) return false;
        CoreInstrumentState targetInstrument = state.instruments().get(target.symbol());
        if (targetInstrument == null) return false;
        String targetAsset = targetInstrument.settleAsset();
        CoreLiquidationState first = state.riskState().liquidations().values().stream()
                .filter(liquidation -> liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED)
                .filter(liquidation -> {
                    CoreInstrumentState instrument = state.instruments().get(liquidation.symbol());
                    return instrument != null && instrument.version() == liquidation.instrumentVersion()
                            && targetAsset.equals(instrument.settleAsset());
                })
                .map(CoreClaim::new)
                .min(CORE_CLAIM_ORDER)
                .map(CoreClaim::liquidation)
                .orElse(null);
        return first != null && first.liquidationId() == liquidationId;
    }

    private static void allocateAsset(long availableUnits, ArrayList<Claim> claims, Map<Long, Long> result) {
        claims.sort(CLAIM_ORDER);
        BigInteger total = BigInteger.ZERO;
        for (Claim claim : claims) total = total.add(BigInteger.valueOf(claim.liquidation().deficitUnits()));
        BigInteger available = BigInteger.valueOf(Math.max(0, availableUnits)).min(total);
        if (available.signum() == 0) {
            for (Claim claim : claims) result.put(claim.liquidation().liquidationId(), 0L);
            return;
        }
        long[] base = new long[claims.size()];
        BigInteger allocated = BigInteger.ZERO;
        for (int index = 0; index < claims.size(); index++) {
            BigInteger share = available.multiply(BigInteger.valueOf(claims.get(index).liquidation().deficitUnits()))
                    .divide(total);
            base[index] = share.longValueExact();
            allocated = allocated.add(share);
        }
        int remainder = available.subtract(allocated).intValueExact();
        for (int index = 0; index < claims.size(); index++) {
            long coverage = base[index] + (index < remainder ? 1 : 0);
            result.put(claims.get(index).liquidation().liquidationId(), coverage);
        }
    }

    private static long allocationForCore(long availableUnits, ArrayList<CoreClaim> claims, long targetId) {
        BigInteger total = BigInteger.ZERO;
        for (CoreClaim claim : claims) total = total.add(BigInteger.valueOf(claim.liquidation().deficitUnits()));
        BigInteger available = BigInteger.valueOf(availableUnits).min(total);
        BigInteger allocated = BigInteger.ZERO;
        long targetBase = 0;
        int targetIndex = -1;
        for (int index = 0; index < claims.size(); index++) {
            CoreLiquidationState liquidation = claims.get(index).liquidation();
            BigInteger share = total.signum() == 0 ? BigInteger.ZERO
                    : available.multiply(BigInteger.valueOf(liquidation.deficitUnits())).divide(total);
            if (liquidation.liquidationId() == targetId) {
                targetBase = share.longValueExact();
                targetIndex = index;
            }
            allocated = allocated.add(share);
        }
        int remainder = available.subtract(allocated).intValueExact();
        return targetIndex < 0 ? 0 : targetBase + (targetIndex < remainder ? 1 : 0);
    }

    private record Claim(LiquidationRuntime liquidation, String symbol) {
    }

    private record CoreClaim(CoreLiquidationState liquidation) {
    }
}
