package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreFundsPostingView;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreLiquidationView;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

interface RuntimeCommitView {
    ProductLine productLine();
    long previousCoreSequence();
    long coreSequence();
    long beforeRevision();
    long beforeFundsStateHash();
    List<RuntimeCommitPatch.AccountLaneOwnerGroup> accountLaneGroups();
    RuntimeCommitPatch.GlobalOwnerGroup globalOwnerGroup();
    RuntimeCommitPatch.FactIdentitySlice identities();
}

public final class RuntimeCommitPatch implements RuntimeCommitView {

    private final ProductLine productLine;
    private final long previousCoreSequence;
    private final long coreSequence;
    private final long previousProjectionSequence;
    private final long projectionSequence;
    private final long beforeRevision;
    private final long afterRevision;
    private final long beforeBusinessStateHash;
    private final long businessStateHash;
    private final long beforeFundsStateHash;
    private final long fundsStateHash;
    private final long laneMask;
    private final List<LaneCommit> laneCommits;
    private final List<AccountLaneOwnerGroup> accountLaneGroups;
    private final GlobalOwnerGroup globalOwnerGroup;
    private final List<OwnerGroup> ownerGroups;
    private final List<FundsPosting> fundsPostings;
    private final RuntimeFundsDelta fundsDelta;
    private final CoreMatcherTransition matcherTransition;
    private final List<MatcherEvidence> matcherEvidence;
    private final TerminalIds terminalIds;
    private final CoreFactValues coreFactValues;
    private final CoreFactMetadata coreFactMetadata;
    private final FactIdentitySlice identities;
    private final RuntimeProjectionPoint projectionPoint;

    private RuntimeCommitPatch(Builder builder, SealMetadata metadata,
                               FactIdentitySlice identities,
                               List<LaneCommit> laneCommits,
                               List<AccountLaneOwnerGroup> accountLaneGroups,
                               GlobalOwnerGroup globalOwnerGroup,
                               List<FundsPosting> fundsPostings,
                               RuntimeFundsDelta fundsDelta,
                               List<MatcherEvidence> matcherEvidence,
                               TerminalIds terminalIds) {
        productLine = builder.productLine;
        previousCoreSequence = builder.previousCoreSequence;
        coreSequence = builder.coreSequence;
        previousProjectionSequence = builder.previousProjectionSequence;
        projectionSequence = builder.projectionSequence;
        beforeRevision = metadata.beforeRevision();
        afterRevision = metadata.afterRevision();
        beforeBusinessStateHash = metadata.beforeBusinessStateHash();
        businessStateHash = metadata.businessStateHash();
        beforeFundsStateHash = metadata.beforeFundsStateHash();
        fundsStateHash = metadata.fundsStateHash();
        laneMask = metadata.laneMask();
        this.laneCommits = laneCommits;
        this.accountLaneGroups = accountLaneGroups;
        this.globalOwnerGroup = globalOwnerGroup;
        ArrayList<OwnerGroup> groups = new ArrayList<>(this.accountLaneGroups.size() + 1);
        groups.addAll(this.accountLaneGroups);
        groups.add(globalOwnerGroup);
        ownerGroups = List.copyOf(groups);
        this.fundsPostings = fundsPostings;
        this.fundsDelta = fundsDelta;
        matcherTransition = builder.matcherTransition;
        this.matcherEvidence = matcherEvidence;
        this.terminalIds = terminalIds;
        coreFactValues = builder.coreFactValues;
        coreFactMetadata = metadata.coreFactMetadata();
        this.identities = identities;
        projectionPoint = new RuntimeProjectionPoint(projectionSequence, null);
    }

    public static Builder builder(ProductLine productLine,
                                  long previousCoreSequence, long coreSequence,
                                  long previousProjectionSequence, long projectionSequence) {
        return new Builder(productLine, previousCoreSequence, coreSequence,
                previousProjectionSequence, projectionSequence);
    }

    static Builder builder(ProductLine productLine) {
        return new Builder(productLine);
    }

    public ProductLine productLine() { return productLine; }
    public long previousCoreSequence() { return previousCoreSequence; }
    public long coreSequence() { return coreSequence; }
    public long previousProjectionSequence() { return previousProjectionSequence; }
    public long projectionSequence() { return projectionSequence; }
    public long beforeRevision() { return beforeRevision; }
    public long afterRevision() { return afterRevision; }
    public long beforeBusinessStateHash() { return beforeBusinessStateHash; }
    public long businessStateHash() { return businessStateHash; }
    public long beforeFundsStateHash() { return beforeFundsStateHash; }
    public long fundsStateHash() { return fundsStateHash; }
    public long laneMask() { return laneMask; }
    public List<LaneCommit> laneCommits() { return laneCommits; }
    public List<AccountLaneOwnerGroup> accountLaneGroups() { return accountLaneGroups; }
    public GlobalOwnerGroup globalOwnerGroup() { return globalOwnerGroup; }
    public List<OwnerGroup> ownerGroups() { return ownerGroups; }
    public List<FundsPosting> fundsPostings() { return fundsPostings; }
    public RuntimeFundsDelta fundsDelta() { return fundsDelta; }
    public CoreMatcherTransition matcherTransition() { return matcherTransition; }
    public List<MatcherEvidence> matcherEvidence() { return matcherEvidence; }
    public TerminalIds terminalIds() { return terminalIds; }
    public CoreFactValues coreFactValues() { return coreFactValues; }
    public CoreFactMetadata coreFactMetadata() { return coreFactMetadata; }
    public FactIdentitySlice identities() {
        if (identities == null) throw new IllegalStateException("patch identities are unavailable");
        return identities;
    }
    public long sequence() { return projectionSequence; }
    public long revision() { return afterRevision; }
    public RuntimeProjectionPoint projectionPoint() { return projectionPoint; }
    public List<Long> changedUserIds() {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        acceptChangedUserIds(ids::add);
        ids.remove(0L);
        return List.copyOf(ids);
    }

    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            group.users().forEach(change -> acceptPositive(consumer, change.userId()));
            group.balances().forEach(change -> acceptPositive(consumer, change.key().userId()));
            group.reservations().forEach(change -> acceptPositive(consumer, reservationUserId(change)));
            group.positions().forEach(change -> acceptPositive(consumer, positionUserId(change)));
        }
    }

    private static void acceptPositive(java.util.function.LongConsumer consumer, long value) {
        if (value > 0) consumer.accept(value);
    }
    public List<Integer> changedTreasuryAssetIds() {
        return globalOwnerGroup.treasuryAssets().stream().map(TreasuryAssetChange::assetId).toList();
    }
    public long afterNextLiquidationId() {
        NextLiquidationIdChange change = globalOwnerGroup.nextLiquidationId();
        return change == null ? 0 : change.after();
    }
    public CoreRiskScanControlView afterRiskScanControl() {
        RiskScanControlChange change = globalOwnerGroup.riskScanControl();
        return change == null ? null : change.after();
    }
    void completeProjection(TradingCoreState state) { projectionPoint.complete(state); }
    public boolean changesBusinessState() { return beforeRevision != afterRevision; }

    public int coreFactItemCount() {
        int count = 0;
        count = Math.addExact(count, fundsPostings.size());
        count = Math.addExact(count, matcherEvidence.size());
        count = Math.addExact(count, terminalIds.orderIds().size());
        count = Math.addExact(count, terminalIds.liquidationIds().size());
        count = Math.addExact(count, terminalIds.triggerOrderIds().size());
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            count = Math.addExact(count, group.users.size());
            count = Math.addExact(count, group.balances.size());
            count = Math.addExact(count, group.reservations.size());
            count = Math.addExact(count, group.orders.size());
            count = Math.addExact(count, group.positions.size());
            count = Math.addExact(count, group.liquidations.size());
            count = Math.addExact(count, group.leverages.size());
            count = Math.addExact(count, group.algoOrders.size());
            count = Math.addExact(count, group.triggerOrders.size());
        }
        return Math.addExact(count, globalOwnerGroup.treasuryAssets.size());
    }

    public long estimatedCoreFactBytes() {
        return Math.addExact(4_096L, Math.multiplyExact((long) coreFactItemCount(), 2_048L));
    }

    public CoreFactFragment materializeCoreFactFragment() {
        FactIdentitySlice registry = identities();
        ArrayList<CoreUserStateView> users = new ArrayList<>();
        ArrayList<CoreOrderState> orders = new ArrayList<>();
        ArrayList<CoreLiquidationView> liquidations = new ArrayList<>();
        ArrayList<CoreTriggerOrderStateView> triggers = new ArrayList<>();
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            appendUsers(users, group, registry);
            group.orders.stream().map(OrderChange::businessAfter).filter(Objects::nonNull)
                    .forEach(orders::add);
            group.liquidations.stream().filter(change -> change.after != null && !change.asset.isBlank())
                    .map(change -> liquidationView(change, registry)).forEach(liquidations::add);
            group.triggerOrders.stream().map(TriggerOrderChange::after).filter(Objects::nonNull)
                    .map(CoreTriggerOrderState::view).forEach(triggers::add);
        }
        ArrayList<CoreTreasuryAssetView> treasury = new ArrayList<>();
        for (TreasuryAssetChange change : globalOwnerGroup.treasuryAssets) {
            TreasuryAssetValue value = change.after;
            if (value == null) continue;
            treasury.add(new CoreTreasuryAssetView(registry.asset(change.assetId), treasuryFee(value),
                    treasuryInsurance(value), treasuryDeficit(value), treasuryLiquidationFee(value),
                    treasuryFundingResidual(value), treasuryRoundingResidual(value), treasuryClearingPnl(value)));
        }
        return new CoreFactFragment(users, orders, liquidations, treasury, triggers, matcherEvidence,
                terminalIds, tombstones(this, registry));
    }

    private static long reservationUserId(ReservationChange change) {
        ReservationRuntime value = change.after() != null ? change.after() : change.before();
        return value == null ? 0 : value.userId();
    }

    private static long positionUserId(PositionChange change) {
        PositionRuntime value = change.after() != null ? change.after() : change.before();
        return value == null ? 0 : value.userId();
    }

    private static void appendUsers(ArrayList<CoreUserStateView> result, AccountLaneOwnerGroup group,
                                    IdentityView identities) {
        if (group.users.isEmpty()) return;
        ArrayList<UserFactBuilder> ordered = new ArrayList<>(group.users.size());
        LongObjectHashMap<UserFactBuilder> byUser = new LongObjectHashMap<>(group.users.size() * 2);
        for (UserChange userChange : group.users) {
            UserRuntime user = userChange.after;
            if (user == null) continue;
            UserFactBuilder builder = new UserFactBuilder(user);
            ordered.add(builder);
            byUser.put(user.userId(), builder);
        }
        for (BalanceChange change : group.balances) {
            UserFactBuilder builder = byUser.get(change.key.userId);
            if (builder != null && change.after != null) builder.balances.add(new CoreBalanceView(
                    identities.asset(change.key.assetId), change.after.availableUnits, change.after.lockedUnits));
        }
        for (ReservationChange change : group.reservations) {
            ReservationRuntime value = change.after;
            UserFactBuilder builder = value == null ? null : byUser.get(value.userId());
            if (builder != null) builder.reservations.add(new CoreReservationView(value.orderId(),
                    identities.symbol(value.symbolId()), value.instrumentVersion(), value.kind(),
                    identities.asset(value.assetId()), value.totalReservedUnits(), value.releasedUnits(),
                    value.consumedUnits(), value.orderQuantitySteps()));
        }
        for (PositionChange change : group.positions) {
            PositionRuntime value = change.after;
            UserFactBuilder builder = value == null ? null : byUser.get(value.userId());
            if (builder != null) builder.positions.add(new CorePositionView(identities.symbol(value.symbolId()),
                    identities.asset(value.assetId()), value.marginMode(), value.positionSide(),
                    value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                    value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits()));
        }
        for (LeverageChange change : group.leverages) {
            UserFactBuilder builder = byUser.get(change.key.userId());
            if (builder != null && change.after != null) builder.leverages.add(
                    new CoreLeverageView(change.key.symbol(), change.key.marginMode(), change.after));
        }
        for (UserFactBuilder builder : ordered) result.add(builder.materialize());
    }

    private static final class UserFactBuilder {
        private final UserRuntime user;
        private final ArrayList<CoreBalanceView> balances = new ArrayList<>();
        private final ArrayList<CoreReservationView> reservations = new ArrayList<>();
        private final ArrayList<CorePositionView> positions = new ArrayList<>();
        private final ArrayList<CoreLeverageView> leverages = new ArrayList<>();

        private UserFactBuilder(UserRuntime user) { this.user = user; }

        private CoreUserStateView materialize() {
            return new CoreUserStateView(user.productLine(), user.userId(), user.revision(), user.positionMode(),
                    balances, reservations, positions, leverages);
        }
    }

    public static CoreOrderStateView exportOrderView(CoreOrderState order) {
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(), order.postOnly(),
                order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.cumulativeFeeUnits(), order.createdAtEpochMillis(), order.updatedAtEpochMillis(),
                order.clusterPosition(), order.status().name(), order.revision());
    }

    private static CoreExportEvent.Tombstones tombstones(RuntimeCommitPatch patch,
                                                          IdentityView identities) {
        ArrayList<Long> users = new ArrayList<>();
        ArrayList<CoreExportEvent.UserAssetKey> balances = new ArrayList<>();
        ArrayList<CoreExportEvent.UserOrderKey> reservations = new ArrayList<>();
        ArrayList<Long> orders = new ArrayList<>();
        ArrayList<CoreExportEvent.UserPositionKey> positions = new ArrayList<>();
        ArrayList<CoreExportEvent.UserLeverageKey> leverages = new ArrayList<>();
        ArrayList<Long> liquidations = new ArrayList<>();
        ArrayList<Long> algos = new ArrayList<>();
        ArrayList<Long> triggers = new ArrayList<>();
        for (AccountLaneOwnerGroup group : patch.accountLaneGroups) {
            group.users.stream().filter(change -> change.after == null)
                    .map(UserChange::userId).forEach(users::add);
            group.balances.stream().filter(change -> change.after == null)
                    .map(change -> new CoreExportEvent.UserAssetKey(change.key.userId,
                            identities.asset(change.key.assetId))).forEach(balances::add);
            group.reservations.stream().filter(change -> change.after == null)
                    .map(change -> new CoreExportEvent.UserOrderKey(change.before.userId(), change.orderId))
                    .forEach(reservations::add);
            group.orders.stream().filter(change -> change.after == null)
                    .map(OrderChange::orderId).forEach(orders::add);
            group.positions.stream().filter(change -> change.after == null)
                    .map(change -> new CoreExportEvent.UserPositionKey(change.before.userId(),
                            identities.symbol(change.before.symbolId()), change.before.positionSide()))
                    .forEach(positions::add);
            group.leverages.stream().filter(change -> change.after == null)
                    .map(change -> new CoreExportEvent.UserLeverageKey(change.key.userId(),
                            change.key.symbol(), change.key.marginMode())).forEach(leverages::add);
            group.liquidations.stream().filter(change -> change.after == null)
                    .map(LiquidationChange::liquidationId).forEach(liquidations::add);
            group.algoOrders.stream().filter(change -> change.after == null)
                    .map(AlgoOrderChange::algoOrderId).forEach(algos::add);
            group.triggerOrders.stream().filter(change -> change.after == null)
                    .map(TriggerOrderChange::triggerOrderId).forEach(triggers::add);
        }
        users.sort(Long::compare);
        balances.sort(java.util.Comparator.comparingLong(CoreExportEvent.UserAssetKey::userId)
                .thenComparing(CoreExportEvent.UserAssetKey::asset));
        reservations.sort(java.util.Comparator.comparingLong(CoreExportEvent.UserOrderKey::userId)
                .thenComparingLong(CoreExportEvent.UserOrderKey::orderId));
        orders.sort(Long::compare);
        positions.sort(java.util.Comparator.comparingLong(CoreExportEvent.UserPositionKey::userId)
                .thenComparing(CoreExportEvent.UserPositionKey::symbol)
                .thenComparing(CoreExportEvent.UserPositionKey::positionSide));
        leverages.sort(java.util.Comparator.comparingLong(CoreExportEvent.UserLeverageKey::userId)
                .thenComparing(CoreExportEvent.UserLeverageKey::symbol)
                .thenComparing(CoreExportEvent.UserLeverageKey::marginMode));
        liquidations.sort(Long::compare);
        algos.sort(Long::compare);
        triggers.sort(Long::compare);
        ArrayList<String> treasury = new ArrayList<>(patch.globalOwnerGroup.treasuryAssets.stream()
                .filter(change -> change.after == null)
                .map(change -> identities.asset(change.assetId)).toList());
        treasury.sort(String::compareTo);
        return new CoreExportEvent.Tombstones(users, balances, reservations, orders, positions, leverages,
                liquidations, algos, triggers, treasury);
    }

    private static CoreLiquidationView liquidationView(LiquidationChange change,
                                                       IdentityView identities) {
        LiquidationRuntime value = change.after;
        return new CoreLiquidationView(value.liquidationId(), value.userId(),
                identities.symbol(value.symbolId()), change.asset, value.marginMode(),
                value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                value.executionPriceTicks(), value.liquidationFeeRatePpm(), value.liquidationFeeUnits(),
                value.status().name());
    }

    private static long treasuryFee(TreasuryAssetValue value) { return value == null ? 0 : value.fee; }
    private static long treasuryInsurance(TreasuryAssetValue value) {
        return value == null ? 0 : value.insurance;
    }
    private static long treasuryDeficit(TreasuryAssetValue value) { return value == null ? 0 : value.deficit; }
    private static long treasuryLiquidationFee(TreasuryAssetValue value) {
        return value == null ? 0 : value.liquidationFee;
    }
    private static long treasuryFundingResidual(TreasuryAssetValue value) {
        return value == null ? 0 : value.fundingResidual;
    }
    private static long treasuryRoundingResidual(TreasuryAssetValue value) {
        return value == null ? 0 : value.roundingResidual;
    }
    private static long treasuryClearingPnl(TreasuryAssetValue value) {
        return value == null ? 0 : value.clearingPnl;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuntimeCommitPatch patch)) return false;
        return previousCoreSequence == patch.previousCoreSequence && coreSequence == patch.coreSequence
                && previousProjectionSequence == patch.previousProjectionSequence
                && projectionSequence == patch.projectionSequence && beforeRevision == patch.beforeRevision
                && afterRevision == patch.afterRevision && beforeBusinessStateHash == patch.beforeBusinessStateHash
                && businessStateHash == patch.businessStateHash && beforeFundsStateHash == patch.beforeFundsStateHash
                && fundsStateHash == patch.fundsStateHash && laneMask == patch.laneMask
                && productLine == patch.productLine && laneCommits.equals(patch.laneCommits)
                && accountLaneGroups.equals(patch.accountLaneGroups)
                && globalOwnerGroup.equals(patch.globalOwnerGroup) && fundsPostings.equals(patch.fundsPostings)
                && matcherTransition.equals(patch.matcherTransition) && matcherEvidence.equals(patch.matcherEvidence)
                && terminalIds.equals(patch.terminalIds) && coreFactValues.equals(patch.coreFactValues)
                && Objects.equals(coreFactMetadata, patch.coreFactMetadata)
                && Objects.equals(identities, patch.identities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productLine, previousCoreSequence, coreSequence, previousProjectionSequence,
                projectionSequence, beforeRevision, afterRevision, beforeBusinessStateHash, businessStateHash,
                beforeFundsStateHash, fundsStateHash, laneMask, laneCommits, accountLaneGroups, globalOwnerGroup,
                fundsPostings, matcherTransition, matcherEvidence, terminalIds, coreFactValues, coreFactMetadata,
                identities);
    }

    public interface IdentityView {
        int assetId(String asset);
        String asset(int assetId);
        String symbol(int symbolId);
        String clientOrderId(long userId, long clientKey);
        String positionKey(long userId, long positionKey);
        RuntimeIdentityRegistry.PositionIdentity positionIdentity(long positionKey);
    }

    public record IdentityValue(int id, String value) implements Comparable<IdentityValue> {
        public IdentityValue {
            if (id < 0 || value == null || value.isBlank()) {
                throw new IllegalArgumentException("invalid patch identity");
            }
        }
        @Override public int compareTo(IdentityValue other) { return Integer.compare(id, other.id); }
    }

    public record ClientIdentityValue(long key, long userId, String clientOrderId)
            implements Comparable<ClientIdentityValue> {
        public ClientIdentityValue {
            if (key <= 0 || userId <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
                throw new IllegalArgumentException("invalid patch client identity");
            }
        }
        @Override public int compareTo(ClientIdentityValue other) { return Long.compare(key, other.key); }
    }

    public record PositionIdentityValue(long key, RuntimeIdentityRegistry.PositionIdentity identity)
            implements Comparable<PositionIdentityValue> {
        public PositionIdentityValue {
            if (key <= 0 || identity == null) throw new IllegalArgumentException("invalid patch position identity");
        }
        @Override public int compareTo(PositionIdentityValue other) { return Long.compare(key, other.key); }
    }

    public record FactIdentitySlice(List<IdentityValue> assets, List<IdentityValue> symbols,
                                    List<ClientIdentityValue> clients,
                                    List<PositionIdentityValue> positions) implements IdentityView {
        private static final FactIdentitySlice EMPTY = new FactIdentitySlice(List.of(), List.of(), List.of(), List.of());

        public FactIdentitySlice {
            assets = canonicalIdentities(assets, "asset");
            symbols = canonicalIdentities(symbols, "symbol");
            clients = canonicalLongIdentities(clients, "client");
            positions = canonicalLongIdentities(positions, "position");
        }

        @Override public String asset(int assetId) { return findIdentity(assets, assetId, "asset"); }
        @Override public int assetId(String asset) {
            for (IdentityValue value : assets) if (value.value().equals(asset)) return value.id();
            throw new IllegalArgumentException("unknown patch asset: " + asset);
        }
        @Override public String symbol(int symbolId) { return findIdentity(symbols, symbolId, "symbol"); }
        @Override public String clientOrderId(long userId, long clientKey) {
            for (ClientIdentityValue value : clients) {
                if (value.key() == clientKey && value.userId() == userId) return value.clientOrderId();
            }
            throw new IllegalArgumentException("unknown patch client key: " + userId + '/' + clientKey);
        }
        @Override public RuntimeIdentityRegistry.PositionIdentity positionIdentity(long positionKey) {
            for (PositionIdentityValue value : positions) if (value.key() == positionKey) return value.identity();
            throw new IllegalArgumentException("unknown patch position key: " + positionKey);
        }
        @Override public String positionKey(long userId, long positionKey) {
            RuntimeIdentityRegistry.PositionIdentity identity = positionIdentity(positionKey);
            if (identity.userId() != userId) {
                throw new IllegalArgumentException("patch position identity user mismatch");
            }
            return identity.positionKey();
        }

        public FactIdentitySlice merge(FactIdentitySlice other) {
            if (other == null || other == EMPTY) return this;
            if (this == EMPTY) return other;
            java.util.TreeMap<Integer, String> assetValues = mergeValues(assets, other.assets, "asset");
            java.util.TreeMap<Integer, String> symbolValues = mergeValues(symbols, other.symbols, "symbol");
            java.util.TreeMap<Long, ClientIdentityValue> clientValues = new java.util.TreeMap<>();
            for (ClientIdentityValue value : clients) clientValues.put(value.key(), value);
            for (ClientIdentityValue value : other.clients) mergeExact(clientValues, value.key(), value, "client");
            java.util.TreeMap<Long, PositionIdentityValue> positionValues = new java.util.TreeMap<>();
            for (PositionIdentityValue value : positions) positionValues.put(value.key(), value);
            for (PositionIdentityValue value : other.positions) mergeExact(positionValues, value.key(), value, "position");
            return new FactIdentitySlice(assetValues.entrySet().stream()
                    .map(entry -> new IdentityValue(entry.getKey(), entry.getValue())).toList(),
                    symbolValues.entrySet().stream()
                            .map(entry -> new IdentityValue(entry.getKey(), entry.getValue())).toList(),
                    List.copyOf(clientValues.values()), List.copyOf(positionValues.values()));
        }

        private static java.util.TreeMap<Integer, String> mergeValues(List<IdentityValue> left,
                                                                       List<IdentityValue> right,
                                                                       String kind) {
            java.util.TreeMap<Integer, String> values = new java.util.TreeMap<>();
            for (IdentityValue value : left) values.put(value.id(), value.value());
            for (IdentityValue value : right) mergeExact(values, value.id(), value.value(), kind);
            return values;
        }

        private static <K, V> void mergeExact(Map<K, V> values, K key, V value, String kind) {
            V previous = values.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException("conflicting patch " + kind + " identity");
            }
        }

        private static FactIdentitySlice capture(List<AccountLaneOwnerGroup> groups, GlobalOwnerGroup global,
                                                 List<FundsPosting> funds,
                                                 RuntimeIdentityRegistry registry) {
            java.util.TreeSet<Integer> assetIds = new java.util.TreeSet<>();
            java.util.TreeSet<Integer> symbolIds = new java.util.TreeSet<>();
            java.util.TreeSet<ClientOrderKey> clientKeys = new java.util.TreeSet<>();
            java.util.TreeSet<Long> positionKeys = new java.util.TreeSet<>();
            for (AccountLaneOwnerGroup group : groups) {
                group.balances().forEach(change -> assetIds.add(change.key().assetId()));
                for (ReservationChange change : group.reservations()) {
                    ReservationRuntime value = change.after() == null ? change.before() : change.after();
                    assetIds.add(value.assetId()); symbolIds.add(value.symbolId());
                }
                for (OrderChange change : group.orders()) {
                    OrderRuntime value = change.after() == null ? change.before() : change.after();
                    symbolIds.add(value.symbolId());
                }
                for (PositionChange change : group.positions()) {
                    PositionRuntime value = change.after() == null ? change.before() : change.after();
                    assetIds.add(value.assetId()); symbolIds.add(value.symbolId()); positionKeys.add(change.positionKey());
                }
                for (LiquidationChange change : group.liquidations()) {
                    LiquidationRuntime value = change.after() == null ? change.before() : change.after();
                    symbolIds.add(value.symbolId());
                }
                for (RiskSnapshotChange change : group.riskSnapshots()) {
                    RiskSnapshotRuntime value = change.after() == null ? change.before() : change.after();
                    symbolIds.add(value.symbolId()); positionKeys.add(change.riskKey());
                }
                clientKeys.addAll(group.clientOrders().stream().map(ClientOrderChange::key).toList());
            }
            global.markPrices().forEach(change -> symbolIds.add(change.symbolId()));
            global.riskScans().forEach(change -> symbolIds.add(change.symbolId()));
            global.treasuryAssets().forEach(change -> assetIds.add(change.assetId()));
            global.treasuryFunding().forEach(change -> symbolIds.add(change.symbolId()));
            global.treasuryLifecycle().forEach(change -> symbolIds.add(change.symbolId()));
            funds.forEach(posting -> assetIds.add(posting.assetId()));
            if (assetIds.isEmpty() && symbolIds.isEmpty() && clientKeys.isEmpty() && positionKeys.isEmpty()
                    || registry == null) return EMPTY;
            List<IdentityValue> assets = assetIds.stream().map(id -> new IdentityValue(id, registry.asset(id))).toList();
            List<IdentityValue> symbols = symbolIds.stream().map(id -> new IdentityValue(id, registry.symbol(id))).toList();
            List<ClientIdentityValue> clients = clientKeys.stream().map(key -> new ClientIdentityValue(
                    key.clientKey(), key.userId(), registry.clientOrderId(key.userId(), key.clientKey()))).toList();
            List<PositionIdentityValue> positions = positionKeys.stream().map(key ->
                    new PositionIdentityValue(key, registry.positionIdentity(key))).toList();
            return new FactIdentitySlice(assets, symbols, clients, positions);
        }

        private static <T extends Comparable<? super T>> List<T> canonicalLongIdentities(List<T> values,
                                                                                          String kind) {
            List<T> copy = values == null ? List.of() : values.stream().sorted().toList();
            for (int index = 1; index < copy.size(); index++) if (copy.get(index - 1).compareTo(copy.get(index)) == 0) {
                throw new IllegalArgumentException("duplicate patch " + kind + " identity");
            }
            return List.copyOf(copy);
        }

        private static List<IdentityValue> canonicalIdentities(List<IdentityValue> values, String kind) {
            return canonicalLongIdentities(values, kind);
        }

        private static String findIdentity(List<IdentityValue> values, int id, String kind) {
            int low = 0, high = values.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                IdentityValue value = values.get(middle);
                if (value.id() == id) return value.value();
                if (value.id() < id) low = middle + 1; else high = middle - 1;
            }
            throw new IllegalArgumentException("unknown patch " + kind + " id: " + id);
        }
    }

    public sealed interface OwnerGroup permits AccountLaneOwnerGroup, GlobalOwnerGroup {
        int ownerOrder();
    }

    public record AccountLaneOwnerGroup(
            int laneId,
            List<UserChange> users,
            List<BalanceChange> balances,
            List<ReservationChange> reservations,
            List<OrderChange> orders,
            List<PositionChange> positions,
            List<LiquidationChange> liquidations,
            List<RiskSnapshotChange> riskSnapshots,
            List<LeverageChange> leverages,
            List<AlgoOrderChange> algoOrders,
            List<TriggerOrderChange> triggerOrders,
            List<ClientOrderChange> clientOrders,
            List<TimerChange> timers) implements OwnerGroup {
        public AccountLaneOwnerGroup {
            if (laneId < 0 || laneId >= Long.SIZE - 1) throw new IllegalArgumentException("invalid lane id");
            users = List.copyOf(users);
            balances = List.copyOf(balances);
            reservations = List.copyOf(reservations);
            orders = List.copyOf(orders);
            positions = List.copyOf(positions);
            liquidations = List.copyOf(liquidations);
            riskSnapshots = List.copyOf(riskSnapshots);
            leverages = List.copyOf(leverages);
            algoOrders = List.copyOf(algoOrders);
            triggerOrders = List.copyOf(triggerOrders);
            clientOrders = List.copyOf(clientOrders);
            timers = List.copyOf(timers);
        }

        @Override public int ownerOrder() { return laneId; }
    }

    public record GlobalOwnerGroup(
            List<MarkPriceChange> markPrices,
            List<RiskScanChange> riskScans,
            List<InstrumentChange> instruments,
            List<TreasuryAssetChange> treasuryAssets,
            List<TreasuryFundingChange> treasuryFunding,
            List<TreasuryLifecycleChange> treasuryLifecycle,
            NextLiquidationIdChange nextLiquidationId,
            RiskScanControlChange riskScanControl) implements OwnerGroup {
        public GlobalOwnerGroup {
            markPrices = List.copyOf(markPrices);
            riskScans = List.copyOf(riskScans);
            instruments = List.copyOf(instruments);
            treasuryAssets = List.copyOf(treasuryAssets);
            treasuryFunding = List.copyOf(treasuryFunding);
            treasuryLifecycle = List.copyOf(treasuryLifecycle);
        }

        @Override public int ownerOrder() { return Integer.MAX_VALUE; }
    }

    public record UserBalance(long availableUnits, long lockedUnits, long pendingReservedUnits) {
        public UserBalance {
            if (availableUnits < 0 || lockedUnits < 0 || pendingReservedUnits < 0
                    || pendingReservedUnits > lockedUnits) {
                throw new IllegalArgumentException("invalid patch balance");
            }
        }
    }

    public record UserChange(long userId, UserRuntime before, UserRuntime after,
                             int pendingReservationCountAfter) {
        public UserChange(long userId, UserRuntime before, UserRuntime after) {
            this(userId, before, after, 0);
        }
        public UserChange {
            if (userId <= 0 || before == null && after == null) {
                throw new IllegalArgumentException("invalid user change");
            }
            if (pendingReservationCountAfter < 0) {
                throw new IllegalArgumentException("invalid pending reservation count");
            }
        }
    }
    public record BalanceKey(long userId, int assetId) implements Comparable<BalanceKey> {
        public BalanceKey { if (userId <= 0 || assetId < 0) throw new IllegalArgumentException("invalid balance key"); }
        @Override public int compareTo(BalanceKey other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : Integer.compare(assetId, other.assetId);
        }
    }
    public record BalanceChange(BalanceKey key, UserBalance before, UserBalance after) {
        public BalanceChange { requireChange(key != null, before, after, "balance"); }
    }
    public record ReservationChange(long orderId, ReservationRuntime before, ReservationRuntime after,
                                    boolean pendingBefore, boolean pendingAfter) {
        public ReservationChange {
            if (orderId <= 0 || before == null && after == null
                    || Objects.equals(before, after) && pendingBefore == pendingAfter) {
                throw new IllegalArgumentException("invalid reservation change");
            }
        }
    }
    public record OrderChange(long orderId, OrderRuntime before, OrderRuntime after,
                              CoreOrderState businessBefore, CoreOrderState businessAfter) {
        public OrderChange(long orderId, OrderRuntime before, OrderRuntime after) {
            this(orderId, before, after, null, null);
        }
        public OrderChange {
            requireChange(orderId > 0, before, after, "order");
            if (businessBefore != null && businessBefore.orderId() != orderId
                    || businessAfter != null && businessAfter.orderId() != orderId) {
                throw new IllegalArgumentException("order business identity mismatch");
            }
        }

        public OrderChange reversed() {
            return new OrderChange(orderId, after, before, businessAfter, businessBefore);
        }
    }
    public record PositionChange(long positionKey, PositionRuntime before, PositionRuntime after) {
        public PositionChange { requireChange(positionKey > 0, before, after, "position"); }
    }
    public record LiquidationChange(long liquidationId, LiquidationRuntime before, LiquidationRuntime after,
                                    String asset) {
        public LiquidationChange(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {
            this(liquidationId, before, after, "");
        }
        public LiquidationChange {
            requireChange(liquidationId > 0, before, after, "liquidation");
            asset = asset == null ? "" : asset;
        }
    }
    public record RiskSnapshotChange(long riskKey, RiskSnapshotRuntime before, RiskSnapshotRuntime after) {
        public RiskSnapshotChange { requireChange(riskKey > 0, before, after, "risk snapshot"); }
    }
    public record LeverageChange(CoreLeverageKey key, Long before, Long after) {
        public LeverageChange { requireChange(key != null, before, after, "leverage"); }
    }
    public record AlgoOrderChange(long algoOrderId, CoreAlgoOrderState before, CoreAlgoOrderState after) {
        public AlgoOrderChange { requireChange(algoOrderId > 0, before, after, "algo order"); }
    }
    public record TriggerOrderChange(long triggerOrderId, CoreTriggerOrderState before, CoreTriggerOrderState after) {
        public TriggerOrderChange { requireChange(triggerOrderId > 0, before, after, "trigger order"); }
    }
    public record ClientOrderKey(long userId, long clientKey) implements Comparable<ClientOrderKey> {
        public ClientOrderKey {
            if (userId <= 0 || clientKey <= 0) throw new IllegalArgumentException("invalid client-order key");
        }
        @Override public int compareTo(ClientOrderKey other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : Long.compare(clientKey, other.clientKey);
        }
    }
    public record ClientOrderChange(ClientOrderKey key, Long beforeOrderId, Long afterOrderId) {
        public ClientOrderChange {
            requireChange(key != null, beforeOrderId, afterOrderId, "client order");
            if (beforeOrderId != null && beforeOrderId <= 0 || afterOrderId != null && afterOrderId <= 0) {
                throw new IllegalArgumentException("invalid client order identity");
            }
        }
    }
    public record TimerChange(CoreCancelAllAfterKey key, CoreCancelAllAfterState before,
                              CoreCancelAllAfterState after) {
        public TimerChange { requireChange(key != null, before, after, "timer"); }
    }
    public record MarkPriceChange(int symbolId, MarkPriceRuntime before, MarkPriceRuntime after) {
        public MarkPriceChange { requireChange(symbolId >= 0, before, after, "mark price"); }
    }
    public record RiskScanChange(int symbolId, RiskScanRuntime before, RiskScanRuntime after) {
        public RiskScanChange { requireChange(symbolId >= 0, before, after, "risk scan"); }
    }
    public record InstrumentChange(String symbol, CoreInstrumentState before, CoreInstrumentState after) {
        public InstrumentChange {
            requireChange(symbol != null && !symbol.isBlank(), before, after, "instrument");
        }
    }
    public record TreasuryAssetValue(long fee, long insurance, long deficit, long liquidationFee,
                                     long fundingResidual, long roundingResidual, long clearingPnl) {}
    public record TreasuryAssetChange(int assetId, TreasuryAssetValue before, TreasuryAssetValue after) {
        public TreasuryAssetChange { requireChange(assetId >= 0, before, after, "treasury asset"); }
    }
    public record TreasuryFundingValue(long settlementId, TreasuryRuntime.FundingProgressRuntime progress) {
        public TreasuryFundingValue { if (settlementId < 0) throw new IllegalArgumentException("invalid funding value"); }
    }
    public record TreasuryFundingChange(int symbolId, TreasuryFundingValue before, TreasuryFundingValue after) {
        public TreasuryFundingChange { requireChange(symbolId >= 0, before, after, "treasury funding"); }
    }
    public record TreasuryLifecycleValue(long settlementId, TreasuryRuntime.LifecycleProgressRuntime progress) {
        public TreasuryLifecycleValue {
            if (settlementId < 0) throw new IllegalArgumentException("invalid lifecycle value");
        }
    }
    public record TreasuryLifecycleChange(int symbolId, TreasuryLifecycleValue before,
                                          TreasuryLifecycleValue after) {
        public TreasuryLifecycleChange { requireChange(symbolId >= 0, before, after, "treasury lifecycle"); }
    }
    public record NextLiquidationIdChange(long before, long after) {
        public NextLiquidationIdChange {
            if (before <= 0 || after <= 0 || before == after) {
                throw new IllegalArgumentException("invalid next-liquidation-id change");
            }
        }
    }
    public record RiskScanControlChange(CoreRiskScanControlView before, CoreRiskScanControlView after) {
        public RiskScanControlChange { requireChange(true, before, after, "risk scan control"); }
    }

    public record LaneCommit(int laneId, long appliedSequence, long committedSequence,
                             int ownerGroupStartInclusive, int ownerGroupEndExclusive,
                             long beforeRevision, long afterRevision,
                             long beforeHash, long afterHash,
                             long beforeFundsHash, long afterFundsHash) implements Comparable<LaneCommit> {
        public LaneCommit {
            if (laneId < 0 || laneId >= Long.SIZE - 1 || appliedSequence <= 0
                    || committedSequence != appliedSequence
                    || ownerGroupStartInclusive < 0
                    || ownerGroupEndExclusive != Math.incrementExact(ownerGroupStartInclusive)
                    || beforeRevision < 0 || afterRevision < beforeRevision) {
                throw new IllegalArgumentException("invalid lane commit");
            }
        }
        public long coreSequence() { return committedSequence; }
        @Override public int compareTo(LaneCommit other) { return Integer.compare(laneId, other.laneId); }
    }

    public record FundsPosting(int assetId,
                               com.surprising.aeron.service.state.FundsPosting.OwnerKind ownerKind,
                               long ownerId,
                               com.surprising.aeron.service.state.FundsPosting.Subledger subledger,
                               long units) implements Comparable<FundsPosting> {
        public FundsPosting {
            if (assetId < 0 || ownerKind == null || subledger == null || units == 0) {
                throw new IllegalArgumentException("invalid patch funds posting");
            }
        }
        @Override public int compareTo(FundsPosting other) {
            int result = Integer.compare(assetId, other.assetId);
            if (result == 0) result = Integer.compare(ownerKind.ordinal(), other.ownerKind.ordinal());
            if (result == 0) result = Long.compare(ownerId, other.ownerId);
            if (result == 0) result = Integer.compare(subledger.ordinal(), other.subledger.ordinal());
            return result;
        }
    }

    public record MatcherEvidence(long matcherSequence, int matcherShardId, long makerOrderId,
                                  long takerOrderId, long quantitySteps, long priceTicks)
            implements Comparable<MatcherEvidence> {
        public MatcherEvidence {
            if (matcherSequence <= 0 || matcherShardId < 0 || makerOrderId <= 0 || takerOrderId <= 0
                    || quantitySteps <= 0 || priceTicks <= 0) {
                throw new IllegalArgumentException("invalid matcher evidence");
            }
        }
        @Override public int compareTo(MatcherEvidence other) {
            int result = Long.compare(matcherSequence, other.matcherSequence);
            if (result == 0) result = Integer.compare(matcherShardId, other.matcherShardId);
            if (result == 0) result = Long.compare(makerOrderId, other.makerOrderId);
            return result != 0 ? result : Long.compare(takerOrderId, other.takerOrderId);
        }
    }

    public record TerminalIds(List<Long> orderIds, List<Long> liquidationIds, List<Long> triggerOrderIds) {
        public TerminalIds {
            orderIds = canonicalIds(orderIds, "order");
            liquidationIds = canonicalIds(liquidationIds, "liquidation");
            triggerOrderIds = canonicalIds(triggerOrderIds, "trigger order");
        }
    }

    public record CoreFactValues(List<CoreExecutionView> executions,
                                 List<CoreFundingPaymentView> fundingPayments,
                                 CoreFundingProgressView fundingProgress,
                                 CoreSettlementProgressView settlementProgress) {
        private static final CoreFactValues EMPTY = new CoreFactValues(List.of(), List.of(), null, null);

        public CoreFactValues {
            executions = List.copyOf(executions == null ? List.of() : executions);
            fundingPayments = List.copyOf(fundingPayments == null ? List.of() : fundingPayments);
        }

        public static CoreFactValues empty() { return EMPTY; }
    }

    public record CoreFactPayload(
            List<CoreUserStateView> changedUsers,
            List<CoreOrderStateView> changedOrders,
            List<CoreExecutionView> executions,
            List<CoreFundingPaymentView> fundingPayments,
            List<CoreLiquidationView> changedLiquidations,
            List<CoreTreasuryAssetView> changedTreasuryAssets,
            List<CoreTriggerOrderStateView> changedTriggerOrders,
            List<CoreFundsPostingView> fundsPostings,
            CoreFundingProgressView fundingProgress,
            CoreSettlementProgressView settlementProgress,
            CoreMatcherTransition matcherTransition,
            List<MatcherEvidence> matcherEvidence,
            TerminalIds terminalIds,
            CoreExportEvent.Tombstones tombstones,
            long previousCoreSequence,
            long coreSequence,
            long previousProjectionSequence,
            long projectionSequence,
            long beforeBusinessStateHash,
            long businessStateHash,
            long beforeFundsStateHash,
            long fundsStateHash,
            long topologyHash,
            long laneRevisionHash,
            long clusterPosition,
            CoreFactMetadata commandMetadata) {

        public CoreFactPayload {
            changedUsers = List.copyOf(changedUsers);
            changedOrders = List.copyOf(changedOrders);
            executions = List.copyOf(executions);
            fundingPayments = List.copyOf(fundingPayments);
            changedLiquidations = List.copyOf(changedLiquidations);
            changedTreasuryAssets = List.copyOf(changedTreasuryAssets);
            changedTriggerOrders = List.copyOf(changedTriggerOrders);
            fundsPostings = List.copyOf(fundsPostings);
            matcherEvidence = List.copyOf(matcherEvidence);
            Objects.requireNonNull(matcherTransition, "matcherTransition");
            Objects.requireNonNull(terminalIds, "terminalIds");
            Objects.requireNonNull(tombstones, "tombstones");
            Objects.requireNonNull(commandMetadata, "commandMetadata");
        }

        public int itemCount() {
            return changedUsers.size() + changedOrders.size() + executions.size() + fundingPayments.size()
                    + changedLiquidations.size() + changedTreasuryAssets.size() + changedTriggerOrders.size()
                    + fundsPostings.size() + matcherEvidence.size() + terminalIds.orderIds().size()
                    + terminalIds.liquidationIds().size() + terminalIds.triggerOrderIds().size()
                    + tombstones.itemCount();
        }

    }

    public record CoreFactFragment(List<CoreUserStateView> changedUsers,
                                   List<CoreOrderState> changedOrders,
                                   List<CoreLiquidationView> changedLiquidations,
                                   List<CoreTreasuryAssetView> changedTreasuryAssets,
                                   List<CoreTriggerOrderStateView> changedTriggerOrders,
                                   List<MatcherEvidence> matcherEvidence,
                                   TerminalIds terminalIds,
                                   CoreExportEvent.Tombstones tombstones) {
        public CoreFactFragment {
            changedUsers = List.copyOf(changedUsers);
            changedOrders = List.copyOf(changedOrders);
            changedLiquidations = List.copyOf(changedLiquidations);
            changedTreasuryAssets = List.copyOf(changedTreasuryAssets);
            changedTriggerOrders = List.copyOf(changedTriggerOrders);
            matcherEvidence = List.copyOf(matcherEvidence);
            Objects.requireNonNull(terminalIds, "terminalIds");
            Objects.requireNonNull(tombstones, "tombstones");
        }
    }

    public record CoreFactMetadata(UUID commandId, CommandFingerprint commandFingerprint,
                                   int messageTypeWireCode, long userId,
                                   ResponseStatus status, CoreResultCode resultCode,
                                   long appliedCommandCount, long clusterPosition,
                                   long topologyHash, long laneRevisionHash,
                                   boolean externalAdjustment) {
        public CoreFactMetadata {
            if (commandId == null || commandFingerprint == null || messageTypeWireCode <= 0 || userId < 0
                    || status == null
                    || resultCode == null || appliedCommandCount < 0 || clusterPosition < 0) {
                throw new IllegalArgumentException("invalid Core Fact metadata");
            }
            boolean nonZeroFingerprint = false;
            for (int index = 0; index < CommandFingerprint.LENGTH; index++) {
                if (commandFingerprint.byteAt(index) != 0) {
                    nonZeroFingerprint = true;
                    break;
                }
            }
            if (!nonZeroFingerprint) throw new IllegalArgumentException("Core Fact fingerprint must not be zero");
        }
    }

    public record SealMetadata(long beforeRevision, long afterRevision,
                               long beforeBusinessStateHash, long businessStateHash,
                               long beforeFundsStateHash, long fundsStateHash,
                               long laneMask, CoreFactMetadata coreFactMetadata,
                               boolean externalAdjustment) {
        public SealMetadata(long beforeRevision, long afterRevision,
                            long beforeBusinessStateHash, long businessStateHash,
                            long beforeFundsStateHash, long fundsStateHash,
                            long laneMask, CoreFactMetadata coreFactMetadata) {
            this(beforeRevision, afterRevision, beforeBusinessStateHash, businessStateHash,
                    beforeFundsStateHash, fundsStateHash, laneMask, coreFactMetadata,
                    coreFactMetadata != null && coreFactMetadata.externalAdjustment());
        }

        public SealMetadata {
            if (beforeRevision < 0 || afterRevision < beforeRevision || laneMask < 0) {
                throw new IllegalArgumentException("invalid patch seal metadata");
            }
        }
    }

    public record PrepareMetadata(long beforeRevision, long afterRevision,
                                  long beforeBusinessStateHash, long beforeFundsStateHash,
                                  long laneMask, CoreFactMetadata coreFactMetadata,
                                  boolean externalAdjustment) {
        public PrepareMetadata {
            if (beforeRevision < 0 || afterRevision < beforeRevision || laneMask < 0) {
                throw new IllegalArgumentException("invalid patch prepare metadata");
            }
        }
    }

    public static final class PreparedChanges implements RuntimeCommitView {
        private final Builder builder;
        private final PrepareMetadata metadata;
        private final FactIdentitySlice identities;
        private final List<LaneCommit> laneCommits;
        private final List<AccountLaneOwnerGroup> accountLaneGroups;
        private final GlobalOwnerGroup globalOwnerGroup;
        private final List<FundsPosting> fundsPostings;
        private final RuntimeFundsDelta fundsDelta;
        private final List<MatcherEvidence> matcherEvidence;
        private final TerminalIds terminalIds;

        private PreparedChanges(Builder builder, PrepareMetadata metadata, FactIdentitySlice identities,
                                List<LaneCommit> laneCommits,
                                List<AccountLaneOwnerGroup> accountLaneGroups,
                                GlobalOwnerGroup globalOwnerGroup,
                                List<FundsPosting> fundsPostings, RuntimeFundsDelta fundsDelta,
                                List<MatcherEvidence> matcherEvidence, TerminalIds terminalIds) {
            this.builder = builder;
            this.metadata = metadata;
            this.identities = identities;
            this.laneCommits = laneCommits;
            this.accountLaneGroups = accountLaneGroups;
            this.globalOwnerGroup = globalOwnerGroup;
            this.fundsPostings = fundsPostings;
            this.fundsDelta = fundsDelta;
            this.matcherEvidence = matcherEvidence;
            this.terminalIds = terminalIds;
        }

        public ProductLine productLine() { return builder.productLine; }
        public long previousCoreSequence() { return builder.previousCoreSequence; }
        public long coreSequence() { return builder.coreSequence; }
        public long beforeRevision() { return metadata.beforeRevision(); }
        public long afterRevision() { return metadata.afterRevision(); }
        public long beforeBusinessStateHash() { return metadata.beforeBusinessStateHash(); }
        public long beforeFundsStateHash() { return metadata.beforeFundsStateHash(); }
        public List<AccountLaneOwnerGroup> accountLaneGroups() { return accountLaneGroups; }
        public GlobalOwnerGroup globalOwnerGroup() { return globalOwnerGroup; }
        public List<FundsPosting> fundsPostings() { return fundsPostings; }
        public RuntimeFundsDelta fundsDelta() { return fundsDelta; }
        public FactIdentitySlice identities() { return identities; }
    }

    public static final class Builder {
        private final ProductLine productLine;
        private long previousCoreSequence;
        private long coreSequence;
        private long previousProjectionSequence;
        private long projectionSequence;
        private boolean sequencesSet;
        private final Map<Integer, LaneChanges> lanes = new HashMap<>();
        private final Map<Integer, LaneCommit> laneCommits = new HashMap<>();
        private long laneMask;
        private final GlobalChanges global = new GlobalChanges();
        private final ArrayList<FundsPosting> fundsPostings = new ArrayList<>();
        private final ArrayList<MatcherEvidence> matcherEvidence = new ArrayList<>();
        private final Map<Long, String> liquidationAssets = new HashMap<>();
        private List<Long> terminalOrderIds = List.of();
        private List<Long> terminalLiquidationIds = List.of();
        private List<Long> terminalTriggerOrderIds = List.of();
        private CoreMatcherTransition matcherTransition;
        private CoreFactValues coreFactValues = CoreFactValues.empty();
        private boolean coreFactValuesSet;
        private boolean terminalIdsSet;
        private boolean sealed;
        private boolean finalSealed;
        private PreparedChanges activePrepared;

        private Builder(ProductLine productLine, long previousCoreSequence, long coreSequence,
                        long previousProjectionSequence, long projectionSequence) {
            this(productLine);
            sequences(previousCoreSequence, coreSequence, previousProjectionSequence, projectionSequence);
        }

        private Builder(ProductLine productLine) {
            this.productLine = Objects.requireNonNull(productLine, "product line");
        }

        Builder sequences(long previousCoreSequence, long coreSequence,
                          long previousProjectionSequence, long projectionSequence) {
            requireOpen();
            if (sequencesSet) throw new IllegalStateException("patch sequences are already set");
            if (productLine == null || previousCoreSequence < 0 || coreSequence <= 0
                    || previousProjectionSequence < 0 || projectionSequence <= 0) {
                throw new IllegalArgumentException("invalid patch sequence metadata");
            }
            if (coreSequence != previousCoreSequence
                    && coreSequence != Math.incrementExact(previousCoreSequence)
                    || projectionSequence != Math.incrementExact(previousProjectionSequence)) {
                throw new IllegalArgumentException("patch sequences must be contiguous");
            }
            this.previousCoreSequence = previousCoreSequence;
            this.coreSequence = coreSequence;
            this.previousProjectionSequence = previousProjectionSequence;
            this.projectionSequence = projectionSequence;
            sequencesSet = true;
            for (LaneCommit commit : laneCommits.values()) requireLaneSequence(commit);
            return this;
        }

        public Builder matcherTransition(CoreMatcherTransition transition) {
            requireOpen();
            if (transition == null) throw new IllegalArgumentException("matcher transition is required");
            if (matcherTransition != null && !matcherTransition.equals(transition)) {
                throw new IllegalArgumentException("conflicting matcher transition");
            }
            matcherTransition = transition;
            return this;
        }

        public Builder addMatcherEvidence(MatcherEvidence evidence) {
            requireOpen();
            matcherEvidence.add(Objects.requireNonNull(evidence, "evidence"));
            return this;
        }

        public Builder terminalIds(Collection<Long> orderIds, Collection<Long> liquidationIds,
                                   Collection<Long> triggerOrderIds) {
            requireOpen();
            if (terminalIdsSet) throw new IllegalArgumentException("duplicate terminal ids metadata");
            terminalOrderIds = copyIds(orderIds);
            terminalLiquidationIds = copyIds(liquidationIds);
            terminalTriggerOrderIds = copyIds(triggerOrderIds);
            terminalIdsSet = true;
            return this;
        }

        public Builder coreFactValues(CoreFactValues values) {
            requireOpen();
            if (coreFactValuesSet) throw new IllegalArgumentException("duplicate Core Fact values metadata");
            coreFactValues = Objects.requireNonNull(values, "values");
            coreFactValuesSet = true;
            return this;
        }

        public Builder addFundsPosting(FundsPosting posting) {
            requireOpen();
            fundsPostings.add(Objects.requireNonNull(posting, "posting"));
            return this;
        }

        public Builder addLaneCommit(LaneCommit commit) {
            requireOpen();
            Objects.requireNonNull(commit, "commit");
            if (sequencesSet) requireLaneSequence(commit);
            if (laneCommits.putIfAbsent(commit.laneId(), commit) != null) {
                throw new IllegalArgumentException("duplicate lane commit");
            }
            laneMask |= 1L << commit.laneId();
            lanes.computeIfAbsent(commit.laneId(), ignored -> new LaneChanges());
            return this;
        }

        long laneMask() {
            requireOpen();
            return laneMask;
        }

        private void requireLaneSequence(LaneCommit commit) {
            if (commit.appliedSequence() != coreSequence || commit.committedSequence() != coreSequence) {
                throw new IllegalArgumentException("lane commit sequence mismatch");
            }
        }

        public Builder recordUser(int laneId, UserRuntime before, UserRuntime after) {
            return recordUser(laneId, before, after, 0);
        }

        public Builder recordUser(int laneId, UserRuntime before, UserRuntime after,
                                  int pendingReservationCountAfter) {
            return recordUser(laneId, before, after, pendingReservationCountAfter, false);
        }

        Builder recordCurrentUser(int laneId, UserRuntime before, UserRuntime after,
                                  int pendingReservationCountAfter) {
            return recordUser(laneId, before, after, pendingReservationCountAfter, true);
        }

        private Builder recordUser(int laneId, UserRuntime before, UserRuntime after,
                                   int pendingReservationCountAfter, boolean forceCurrent) {
            requireProductLine(before);
            requireProductLine(after);
            if (pendingReservationCountAfter < 0) {
                throw new IllegalArgumentException("invalid pending reservation count");
            }
            long key = before != null ? before.userId() : requireNonNull(after, "user").userId();
            requireEntityId(key, before == null ? key : before.userId(), after == null ? key : after.userId(),
                    "user");
            lane(laneId).users.record(key,
                    before == null ? null : new UserValue(before, 0, false),
                    after == null ? null : new UserValue(after, pendingReservationCountAfter, forceCurrent));
            return this;
        }

        public Builder recordBalance(int laneId, long userId, int assetId,
                                     UserBalance before, UserBalance after) {
            lane(laneId).balances.record(new BalanceKey(userId, assetId), before, after);
            return this;
        }

        public Builder recordReservation(int laneId, long orderId, ReservationRuntime before,
                                         ReservationRuntime after, boolean pendingBefore, boolean pendingAfter) {
            requireEntityId(orderId, before == null ? orderId : before.orderId(),
                    after == null ? orderId : after.orderId(), "reservation");
            lane(laneId).reservations.record(orderId, new ReservationValue(before, pendingBefore),
                    new ReservationValue(after, pendingAfter));
            return this;
        }

        public Builder recordOrder(int laneId, OrderRuntime before, OrderRuntime after,
                                   CoreOrderState businessBefore, CoreOrderState businessAfter) {
            requireProductLine(before);
            requireProductLine(after);
            long key = before != null ? before.orderId() : requireNonNull(after, "order").orderId();
            requireEntityId(key, before == null ? key : before.orderId(), after == null ? key : after.orderId(),
                    "order");
            if (businessBefore == null != (before == null) || businessAfter == null != (after == null)) {
                throw new IllegalArgumentException("typed order patch values are required");
            }
            if (businessBefore != null && businessBefore.orderId() != key
                    || businessAfter != null && businessAfter.orderId() != key) {
                throw new IllegalArgumentException("typed order patch identity mismatch");
            }
            lane(laneId).orders.record(key, new OrderValue(before, businessBefore),
                    new OrderValue(after, businessAfter));
            return this;
        }

        public Builder recordPosition(int laneId, long positionKey, PositionRuntime before, PositionRuntime after) {
            if (before != null && after != null && (before.userId() != after.userId()
                    || before.symbolId() != after.symbolId() || before.marginMode() != after.marginMode()
                    || before.positionSide() != after.positionSide())) {
                throw new IllegalArgumentException("position identity mismatch");
            }
            lane(laneId).positions.record(positionKey, before, after);
            return this;
        }

        Builder capturePositionBefore(int laneId, long positionKey, PositionRuntime before) {
            lane(laneId).positions.captureBefore(positionKey, before);
            return this;
        }

        PositionRuntime positionBefore(int laneId, long positionKey) {
            return lane(laneId).positions.before(positionKey);
        }

        boolean hasPositionCheckpoint(int laneId, long positionKey) {
            return lane(laneId).positions.contains(positionKey);
        }

        public Builder recordLiquidation(int laneId, long id, LiquidationRuntime before, LiquidationRuntime after) {
            requireEntityId(id, before == null ? id : before.liquidationId(),
                    after == null ? id : after.liquidationId(), "liquidation");
            lane(laneId).liquidations.record(id, before, after);
            return this;
        }
        public Builder recordLiquidation(int laneId, long id, LiquidationRuntime before,
                                         LiquidationRuntime after, String asset) {
            if (asset == null || asset.isBlank()) throw new IllegalArgumentException("liquidation asset is required");
            recordLiquidation(laneId, id, before, after);
            String previous = liquidationAssets.putIfAbsent(id, asset);
            if (previous != null && !previous.equals(asset)) {
                throw new IllegalArgumentException("conflicting liquidation asset");
            }
            return this;
        }
        public Builder recordRiskSnapshot(int laneId, long key, RiskSnapshotRuntime before, RiskSnapshotRuntime after) {
            if (before != null && after != null && (before.userId() != after.userId()
                    || before.symbolId() != after.symbolId() || before.positionSide() != after.positionSide())) {
                throw new IllegalArgumentException("risk snapshot identity mismatch");
            }
            lane(laneId).riskSnapshots.record(key, before, after);
            return this;
        }
        public Builder recordLeverage(int laneId, CoreLeverageKey key, Long before, Long after) {
            lane(laneId).leverages.record(key, before, after);
            return this;
        }
        public Builder recordAlgoOrder(int laneId, long id, CoreAlgoOrderState before, CoreAlgoOrderState after) {
            requireEntityId(id, before == null ? id : before.algoOrderId(),
                    after == null ? id : after.algoOrderId(), "algo order");
            lane(laneId).algoOrders.record(id, before, after);
            return this;
        }
        public Builder recordTriggerOrder(int laneId, long id,
                                          CoreTriggerOrderState before, CoreTriggerOrderState after) {
            requireProductLine(before);
            requireProductLine(after);
            requireEntityId(id, before == null ? id : before.triggerOrderId(),
                    after == null ? id : after.triggerOrderId(), "trigger order");
            lane(laneId).triggerOrders.record(id, before, after);
            return this;
        }
        public Builder recordClientOrder(int laneId, ClientOrderKey key, Long before, Long after) {
            lane(laneId).clientOrders.record(key, before, after);
            return this;
        }
        public Builder recordTimer(int laneId, CoreCancelAllAfterKey key,
                                   CoreCancelAllAfterState before, CoreCancelAllAfterState after) {
            lane(laneId).timers.record(key, before, after);
            return this;
        }
        public Builder recordMarkPrice(int symbolId, MarkPriceRuntime before, MarkPriceRuntime after) {
            requireOpen();
            requireEntityId(symbolId, before == null ? symbolId : before.symbolId(),
                    after == null ? symbolId : after.symbolId(), "mark price");
            global.markPrices.record(symbolId, before, after);
            return this;
        }
        public Builder recordRiskScan(int symbolId, RiskScanRuntime before, RiskScanRuntime after) {
            requireOpen();
            requireEntityId(symbolId, before == null ? symbolId : before.symbolId(),
                    after == null ? symbolId : after.symbolId(), "risk scan");
            global.riskScans.record(symbolId, before, after);
            return this;
        }
        public Builder recordInstrument(String symbol, CoreInstrumentState before, CoreInstrumentState after) {
            requireOpen();
            requireProductLine(before);
            requireProductLine(after);
            if (before != null && !before.symbol().equals(symbol)
                    || after != null && !after.symbol().equals(symbol)) {
                throw new IllegalArgumentException("instrument identity mismatch");
            }
            global.instruments.record(symbol, before, after);
            return this;
        }
        public Builder recordTreasuryAsset(int assetId, TreasuryAssetValue before, TreasuryAssetValue after) {
            requireOpen(); global.treasuryAssets.record(assetId, before, after); return this;
        }
        public Builder recordTreasuryFunding(int symbolId, TreasuryFundingValue before, TreasuryFundingValue after) {
            requireOpen(); global.treasuryFunding.record(symbolId, before, after); return this;
        }
        public Builder recordTreasuryLifecycle(int symbolId, TreasuryLifecycleValue before,
                                               TreasuryLifecycleValue after) {
            requireOpen(); global.treasuryLifecycle.record(symbolId, before, after); return this;
        }
        public Builder recordNextLiquidationId(long before, long after) {
            requireOpen(); global.nextLiquidationId.record(UnitKey.VALUE, before, after); return this;
        }
        public Builder recordRiskScanControl(CoreRiskScanControlView before, CoreRiskScanControlView after) {
            requireOpen(); global.riskScanControl.record(UnitKey.VALUE, before, after); return this;
        }

        public RuntimeCommitPatch seal(SealMetadata metadata) {
            PreparedChanges prepared = prepare(new PrepareMetadata(
                    metadata.beforeRevision(), metadata.afterRevision(), metadata.beforeBusinessStateHash(),
                    metadata.beforeFundsStateHash(), metadata.laneMask(), metadata.coreFactMetadata(),
                    metadata.externalAdjustment()), null);
            return seal(prepared, metadata.businessStateHash(), metadata.fundsStateHash());
        }

        public PreparedChanges prepare(PrepareMetadata metadata, RuntimeIdentityRegistry identities) {
            Objects.requireNonNull(metadata, "metadata");
            SealMetadata provisional = new SealMetadata(metadata.beforeRevision(), metadata.afterRevision(),
                    metadata.beforeBusinessStateHash(), metadata.beforeBusinessStateHash(),
                    metadata.beforeFundsStateHash(), metadata.beforeFundsStateHash(), metadata.laneMask(),
                    metadata.coreFactMetadata(), metadata.externalAdjustment());
            return prepareInternal(provisional, identities, metadata);
        }

        public RuntimeCommitPatch seal(PreparedChanges prepared, long businessStateHash, long fundsStateHash) {
            if (prepared == null || prepared.builder != this || prepared != activePrepared) {
                throw new IllegalArgumentException("prepared changes belong to a different builder");
            }
            if (finalSealed) throw new IllegalStateException("patch builder is already sealed");
            PrepareMetadata metadata = prepared.metadata;
            SealMetadata sealedMetadata = new SealMetadata(metadata.beforeRevision(), metadata.afterRevision(),
                    metadata.beforeBusinessStateHash(), businessStateHash,
                    metadata.beforeFundsStateHash(), fundsStateHash, metadata.laneMask(),
                    metadata.coreFactMetadata(), metadata.externalAdjustment());
            RuntimeCommitPatch patch = new RuntimeCommitPatch(this, sealedMetadata, prepared.identities,
                    prepared.laneCommits, prepared.accountLaneGroups, prepared.globalOwnerGroup,
                    prepared.fundsPostings, prepared.fundsDelta, prepared.matcherEvidence, prepared.terminalIds);
            finalSealed = true;
            return patch;
        }

        private PreparedChanges prepareInternal(SealMetadata metadata, RuntimeIdentityRegistry identities,
                                                PrepareMetadata prepareMetadata) {
            requireOpen();
            Objects.requireNonNull(metadata, "metadata");
            if (!sequencesSet) throw new IllegalStateException("patch sequences are required");
            if (matcherTransition == null) throw new IllegalArgumentException("matcher transition is required");
            if (coreSequence == previousCoreSequence
                    && (metadata.coreFactMetadata() == null
                    || metadata.coreFactMetadata().messageTypeWireCode()
                    != CoreMessageType.ACK_EXPORT.wireCode())) {
                throw new IllegalArgumentException("projection-only patch requires ACK_EXPORT metadata");
            }
            if (metadata.coreFactMetadata() != null
                    && metadata.coreFactMetadata().appliedCommandCount() != coreSequence) {
                throw new IllegalArgumentException("Core Fact command count must equal core sequence");
            }
            List<LaneCommit> commits = laneCommits.values().stream().sorted().toList();
            java.util.Set<Integer> changedLaneIds = changedLaneIds();
            if (!laneCommits.keySet().equals(changedLaneIds)) {
                throw new IllegalArgumentException("every changed lane must have exactly one lane commit"
                        + " changed=" + changedLaneIds + " commits=" + laneCommits.keySet());
            }
            if (laneMask != metadata.laneMask()) throw new IllegalArgumentException("lane mask mismatch");
            ArrayList<AccountLaneOwnerGroup> groups = new ArrayList<>(commits.size());
            for (int ownerGroupOffset = 0; ownerGroupOffset < commits.size(); ownerGroupOffset++) {
                LaneCommit commit = commits.get(ownerGroupOffset);
                if (commit.ownerGroupStartInclusive() != ownerGroupOffset
                        || commit.ownerGroupEndExclusive() != ownerGroupOffset + 1) {
                    throw new IllegalArgumentException("lane commit owner-group offset mismatch");
                }
                groups.add(lanes.get(commit.laneId()).seal(commit.laneId()));
            }

            GlobalOwnerGroup sealedGlobal = global.seal();
            ArrayList<FundsPosting> derivedPostings = new ArrayList<>(fundsPostings);
            deriveFundsPostings(groups, sealedGlobal, derivedPostings);
            RuntimeFundsDelta primitiveFunds = RuntimeFundsDelta.from(derivedPostings.stream()
                    .map(posting -> new RuntimeFundsDelta.Posting(posting.assetId(), posting.ownerKind(),
                            posting.ownerId(), posting.subledger(), posting.units()))
                    .toList());
            primitiveFunds.requireConserved(metadata.externalAdjustment());
            List<FundsPosting> canonicalFunds = primitiveFunds.postings().stream()
                    .map(posting -> new FundsPosting(posting.assetId(), posting.ownerKind(), posting.ownerId(),
                            posting.subledger(), posting.units()))
                    .toList();
            List<MatcherEvidence> canonicalEvidence = canonicalDistinct(
                    matcherEvidence, "duplicate matcher evidence");
            TerminalIds terminals = terminalIdsSet
                    ? new TerminalIds(terminalOrderIds, terminalLiquidationIds, terminalTriggerOrderIds)
                    : terminalIds(groups);
            FactIdentitySlice identitySlice = FactIdentitySlice.capture(groups, sealedGlobal,
                    canonicalFunds, identities);
            PreparedChanges prepared = new PreparedChanges(this, prepareMetadata, identitySlice,
                    List.copyOf(commits), List.copyOf(groups), sealedGlobal, canonicalFunds,
                    primitiveFunds, canonicalEvidence, terminals);
            activePrepared = prepared;
            sealed = true;
            return prepared;
        }

        private static TerminalIds terminalIds(List<AccountLaneOwnerGroup> groups) {
            ArrayList<Long> orders = new ArrayList<>();
            ArrayList<Long> liquidations = new ArrayList<>();
            ArrayList<Long> triggers = new ArrayList<>();
            for (AccountLaneOwnerGroup group : groups) {
                group.orders.stream().filter(change -> change.after != null && change.after.status().terminal())
                        .map(OrderChange::orderId).forEach(orders::add);
                group.liquidations.stream().filter(change -> change.after != null
                                && (change.after.status() == CoreLiquidationState.Status.CANCELED
                                || change.after.status() == CoreLiquidationState.Status.COMPLETED
                                && change.after.deficitUnits() == 0))
                        .map(LiquidationChange::liquidationId).forEach(liquidations::add);
                group.triggerOrders.stream().filter(change -> change.after != null && !change.after.status().open())
                        .map(TriggerOrderChange::triggerOrderId).forEach(triggers::add);
            }
            return new TerminalIds(orders, liquidations, triggers);
        }

        private static void deriveFundsPostings(List<AccountLaneOwnerGroup> groups, GlobalOwnerGroup global,
                                                List<FundsPosting> postings) {
            for (AccountLaneOwnerGroup group : groups) {
                for (BalanceChange change : group.balances()) {
                    addPosting(postings, change.key().assetId(),
                            com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER,
                            change.key().userId(),
                            com.surprising.aeron.service.state.FundsPosting.Subledger.AVAILABLE,
                            Math.subtractExact(available(change.after()), available(change.before())));
                    addPosting(postings, change.key().assetId(),
                            com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER,
                            change.key().userId(),
                            com.surprising.aeron.service.state.FundsPosting.Subledger.LOCKED,
                            Math.subtractExact(locked(change.after()), locked(change.before())));
                }
            }
            for (TreasuryAssetChange change : global.treasuryAssets()) {
                TreasuryAssetValue before = change.before();
                TreasuryAssetValue after = change.after();
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.FEE,
                        Math.subtractExact(treasuryFee(after), treasuryFee(before)));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.INSURANCE,
                        Math.subtractExact(treasuryInsurance(after), treasuryInsurance(before)));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT,
                        Math.negateExact(Math.subtractExact(treasuryDeficit(after), treasuryDeficit(before))));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.LIQUIDATION_FEE,
                        Math.subtractExact(treasuryLiquidationFee(after), treasuryLiquidationFee(before)));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.FUNDING_RESIDUAL,
                        Math.subtractExact(treasuryFundingResidual(after), treasuryFundingResidual(before)));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.ROUNDING_RESIDUAL,
                        Math.subtractExact(treasuryRoundingResidual(after), treasuryRoundingResidual(before)));
                addPosting(postings, change.assetId(),
                        com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.CLEARING_PNL,
                        Math.subtractExact(treasuryClearingPnl(after), treasuryClearingPnl(before)));
            }
        }

        private static void addPosting(List<FundsPosting> postings, int assetId,
                                       com.surprising.aeron.service.state.FundsPosting.OwnerKind ownerKind,
                                       long ownerId,
                                       com.surprising.aeron.service.state.FundsPosting.Subledger subledger,
                                       long units) {
            if (units != 0) postings.add(new FundsPosting(assetId, ownerKind, ownerId, subledger, units));
        }

        private static long available(UserBalance value) {
            return value == null ? 0 : Math.addExact(value.availableUnits(), value.pendingReservedUnits());
        }

        private static long locked(UserBalance value) {
            return value == null ? 0 : Math.subtractExact(value.lockedUnits(), value.pendingReservedUnits());
        }

        private static long treasuryFee(TreasuryAssetValue value) { return value == null ? 0 : value.fee(); }
        private static long treasuryInsurance(TreasuryAssetValue value) {
            return value == null ? 0 : value.insurance();
        }
        private static long treasuryDeficit(TreasuryAssetValue value) {
            return value == null ? 0 : value.deficit();
        }
        private static long treasuryLiquidationFee(TreasuryAssetValue value) {
            return value == null ? 0 : value.liquidationFee();
        }
        private static long treasuryFundingResidual(TreasuryAssetValue value) {
            return value == null ? 0 : value.fundingResidual();
        }
        private static long treasuryRoundingResidual(TreasuryAssetValue value) {
            return value == null ? 0 : value.roundingResidual();
        }
        private static long treasuryClearingPnl(TreasuryAssetValue value) {
            return value == null ? 0 : value.clearingPnl();
        }

        java.util.Set<Integer> changedLaneIds() {
            java.util.HashSet<Integer> changed = new java.util.HashSet<>();
            lanes.forEach((laneId, values) -> {
                if (values.hasChanges()) changed.add(laneId);
            });
            return java.util.Set.copyOf(changed);
        }

        private LaneChanges lane(int laneId) {
            requireOpen();
            if (laneId < 0 || laneId >= Long.SIZE - 1) throw new IllegalArgumentException("invalid lane id");
            return lanes.computeIfAbsent(laneId, ignored -> new LaneChanges());
        }

        private void requireProductLine(UserRuntime value) {
            requireOpen();
            if (value != null && value.productLine() != productLine) {
                throw new IllegalArgumentException("patch user product line mismatch");
            }
        }

        private void requireProductLine(OrderRuntime value) {
            requireOpen();
            if (value != null && value.productLine() != productLine) {
                throw new IllegalArgumentException("patch order product line mismatch");
            }
        }

        private void requireProductLine(CoreInstrumentState value) {
            requireOpen();
            if (value != null && value.contractType().productLine() != productLine) {
                throw new IllegalArgumentException("patch instrument product line mismatch");
            }
        }

        private void requireProductLine(CoreTriggerOrderState value) {
            requireOpen();
            if (value != null && value.productLine() != productLine) {
                throw new IllegalArgumentException("patch trigger order product line mismatch");
            }
        }

        private void requireOpen() {
            if (sealed) throw new IllegalStateException("runtime commit patch builder is sealed");
        }
    }

    private static final class LaneChanges {
        private final Changes<Long, UserValue> users = new Changes<>();
        private final Changes<BalanceKey, UserBalance> balances = new Changes<>();
        private final Changes<Long, ReservationValue> reservations = new Changes<>();
        private final Changes<Long, OrderValue> orders = new Changes<>();
        private final Changes<Long, PositionRuntime> positions = new Changes<>();
        private final Changes<Long, LiquidationRuntime> liquidations = new Changes<>();
        private final Changes<Long, RiskSnapshotRuntime> riskSnapshots = new Changes<>();
        private final Changes<CoreLeverageKey, Long> leverages = new Changes<>();
        private final Changes<Long, CoreAlgoOrderState> algoOrders = new Changes<>();
        private final Changes<Long, CoreTriggerOrderState> triggerOrders = new Changes<>();
        private final Changes<ClientOrderKey, Long> clientOrders = new Changes<>();
        private final Changes<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new Changes<>();

        private boolean hasChanges() {
            return users.hasChanges() || balances.hasChanges() || reservations.hasChanges()
                    || orders.hasChanges() || positions.hasChanges() || liquidations.hasChanges()
                    || riskSnapshots.hasChanges() || leverages.hasChanges() || algoOrders.hasChanges()
                    || triggerOrders.hasChanges() || clientOrders.hasChanges() || timers.hasChanges();
        }

        private AccountLaneOwnerGroup seal(int laneId) {
            return new AccountLaneOwnerGroup(laneId,
                    users.seal((key, change) -> new UserChange(key,
                            change.before == null ? null : change.before.value(),
                            change.after == null ? null : change.after.value(),
                            change.after == null ? 0 : change.after.pendingReservationCount())),
                    balances.seal((key, change) -> new BalanceChange(key, change.before, change.after)),
                    reservations.seal((key, change) -> new ReservationChange(key,
                            change.before == null ? null : change.before.value(),
                            change.after == null ? null : change.after.value(),
                            change.before != null && change.before.pending(),
                            change.after != null && change.after.pending())),
                    orders.seal((key, change) -> new OrderChange(key,
                            change.before.runtime(), change.after.runtime(),
                            change.before.business(), change.after.business())),
                    positions.seal((key, change) -> new PositionChange(key, change.before, change.after)),
                    liquidations.seal((key, change) -> new LiquidationChange(key, change.before, change.after)),
                    riskSnapshots.seal((key, change) -> new RiskSnapshotChange(key, change.before, change.after)),
                    leverages.seal((key, change) -> new LeverageChange(key, change.before, change.after)),
                    algoOrders.seal((key, change) -> new AlgoOrderChange(key, change.before, change.after)),
                    triggerOrders.seal((key, change) -> new TriggerOrderChange(key, change.before, change.after)),
                    clientOrders.seal((key, change) -> new ClientOrderChange(key, change.before, change.after)),
                    timers.seal((key, change) -> new TimerChange(key, change.before, change.after)));
        }
    }

    private static final class GlobalChanges {
        private final Changes<Integer, MarkPriceRuntime> markPrices = new Changes<>();
        private final Changes<Integer, RiskScanRuntime> riskScans = new Changes<>();
        private final Changes<String, CoreInstrumentState> instruments = new Changes<>();
        private final Changes<Integer, TreasuryAssetValue> treasuryAssets = new Changes<>();
        private final Changes<Integer, TreasuryFundingValue> treasuryFunding = new Changes<>();
        private final Changes<Integer, TreasuryLifecycleValue> treasuryLifecycle = new Changes<>();
        private final Changes<UnitKey, Long> nextLiquidationId = new Changes<>();
        private final Changes<UnitKey, CoreRiskScanControlView> riskScanControl = new Changes<>();

        private GlobalOwnerGroup seal() {
            return new GlobalOwnerGroup(
                    markPrices.seal((key, change) -> new MarkPriceChange(key, change.before, change.after)),
                    riskScans.seal((key, change) -> new RiskScanChange(key, change.before, change.after)),
                    instruments.seal((key, change) -> new InstrumentChange(key, change.before, change.after)),
                    treasuryAssets.seal((key, change) -> new TreasuryAssetChange(key, change.before, change.after)),
                    treasuryFunding.seal((key, change) -> new TreasuryFundingChange(key,
                            change.before, change.after)),
                    treasuryLifecycle.seal((key, change) -> new TreasuryLifecycleChange(key,
                            change.before, change.after)),
                    nextLiquidationId.single(change -> new NextLiquidationIdChange(change.before, change.after)),
                    riskScanControl.single(change -> new RiskScanControlChange(change.before, change.after)));
        }
    }

    private static final class Changes<K extends Comparable<? super K>, V> {
        private final HashMap<K, BeforeAfter<V>> values = new HashMap<>();

        private void captureBefore(K key, V before) {
            if (key == null) throw new IllegalArgumentException("invalid typed patch key");
            values.putIfAbsent(key, new BeforeAfter<>(before, before));
        }

        private V before(K key) {
            BeforeAfter<V> captured = values.get(key);
            return captured == null ? null : captured.before;
        }

        private boolean contains(K key) {
            return values.containsKey(key);
        }

        private boolean hasChanges() {
            for (BeforeAfter<V> change : values.values()) {
                if (!Objects.equals(change.before, change.after)) return true;
            }
            return false;
        }

        private void record(K key, V before, V after) {
            if (key == null || before == null && after == null) {
                throw new IllegalArgumentException("invalid typed patch change");
            }
            BeforeAfter<V> existing = values.get(key);
            if (existing == null) {
                values.put(key, new BeforeAfter<>(before, after));
                return;
            }
            if (!Objects.equals(before, existing.before) && !Objects.equals(before, existing.after)) {
                throw new IllegalArgumentException("conflicting before-value for patch key " + key);
            }
            existing.after = after;
        }

        private <R> List<R> seal(BiFunction<K, BeforeAfter<V>, R> materializer) {
            ArrayList<R> result = new ArrayList<>(values.size());
            ArrayList<Map.Entry<K, BeforeAfter<V>>> ordered = new ArrayList<>(values.entrySet());
            ordered.sort(Map.Entry.comparingByKey());
            ordered.forEach(entry -> {
                K key = entry.getKey();
                BeforeAfter<V> change = entry.getValue();
                if (!Objects.equals(change.before, change.after)) result.add(materializer.apply(key, change));
            });
            return java.util.Collections.unmodifiableList(result);
        }

        private <R> R single(java.util.function.Function<BeforeAfter<V>, R> materializer) {
            if (values.isEmpty()) return null;
            BeforeAfter<V> change = values.values().iterator().next();
            return Objects.equals(change.before, change.after) ? null : materializer.apply(change);
        }
    }

    private static final class BeforeAfter<V> {
        private final V before;
        private V after;

        private BeforeAfter(V before, V after) {
            this.before = before;
            this.after = after;
        }
    }
    private record ReservationValue(ReservationRuntime value, boolean pending) {
        private ReservationValue {
            if (value == null && pending) throw new IllegalArgumentException("absent reservation cannot be pending");
        }
    }
    private record OrderValue(OrderRuntime runtime, CoreOrderState business) {}
    private record UserValue(UserRuntime value, int pendingReservationCount, boolean forceCurrent) {}
    private enum UnitKey { VALUE }

    private static void requireChange(boolean validKey, Object before, Object after, String domain) {
        if (!validKey || before == null && after == null || Objects.equals(before, after)) {
            throw new IllegalArgumentException("invalid " + domain + " change");
        }
    }

    private static <T> T requireNonNull(T value, String domain) {
        if (value == null) throw new IllegalArgumentException(domain + " value is required");
        return value;
    }

    private static void requireEntityId(long expected, long before, long after, String domain) {
        if (expected < 0 || before != expected || after != expected) {
            throw new IllegalArgumentException(domain + " identity mismatch");
        }
    }

    private static List<Long> copyIds(Collection<Long> ids) {
        if (ids == null) throw new IllegalArgumentException("terminal ids are required");
        return List.copyOf(ids);
    }

    private static List<Long> canonicalIds(List<Long> ids, String domain) {
        if (ids == null) throw new IllegalArgumentException("terminal ids are required");
        ArrayList<Long> ordered = new ArrayList<>(ids);
        ordered.sort(null);
        Long previous = null;
        for (Long id : ordered) {
            if (id == null || id <= 0) throw new IllegalArgumentException("invalid terminal " + domain + " id");
            if (id.equals(previous)) throw new IllegalArgumentException("duplicate terminal " + domain + " id");
            previous = id;
        }
        return List.copyOf(ordered);
    }

    private static <T extends Comparable<? super T>> List<T> canonicalDistinct(List<T> values, String message) {
        ArrayList<T> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.naturalOrder());
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).compareTo(ordered.get(index)) == 0) {
                throw new IllegalArgumentException(message);
            }
        }
        return List.copyOf(ordered);
    }
}
