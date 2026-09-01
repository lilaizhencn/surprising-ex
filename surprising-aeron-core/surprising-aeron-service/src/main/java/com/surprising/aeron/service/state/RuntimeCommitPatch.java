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
    private final long previousSequence;
    private final long sequence;
    private final long beforeRevision;
    private final long afterRevision;
    private final long beforeBusinessStateHash;
    private final long businessStateHash;
    private final long beforeFundsStateHash;
    private final long fundsStateHash;
    private final long laneMask;
    private final List<AccountLaneOwnerGroup> accountLaneGroups;
    private final GlobalOwnerGroup globalOwnerGroup;
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
                               List<AccountLaneOwnerGroup> accountLaneGroups,
                               GlobalOwnerGroup globalOwnerGroup,
                               List<FundsPosting> fundsPostings,
                               RuntimeFundsDelta fundsDelta,
                               List<MatcherEvidence> matcherEvidence,
                               TerminalIds terminalIds) {
        productLine = builder.productLine;
        previousSequence = builder.previousSequence;
        sequence = builder.sequence;
        beforeRevision = metadata.beforeRevision();
        afterRevision = metadata.afterRevision();
        beforeBusinessStateHash = metadata.beforeBusinessStateHash();
        businessStateHash = metadata.businessStateHash();
        beforeFundsStateHash = metadata.beforeFundsStateHash();
        fundsStateHash = metadata.fundsStateHash();
        laneMask = metadata.laneMask();
        this.accountLaneGroups = accountLaneGroups;
        this.globalOwnerGroup = globalOwnerGroup;
        this.fundsPostings = fundsPostings;
        this.fundsDelta = fundsDelta;
        matcherTransition = builder.matcherTransition;
        this.matcherEvidence = matcherEvidence;
        this.terminalIds = terminalIds;
        coreFactValues = builder.coreFactValues;
        coreFactMetadata = metadata.coreFactMetadata();
        this.identities = identities;
        projectionPoint = new RuntimeProjectionPoint(sequence, null);
    }

    public static Builder builder(ProductLine productLine,
                                  long previousSequence, long sequence) {
        return new Builder(productLine, previousSequence, sequence);
    }

    static Builder builder(ProductLine productLine) {
        return new Builder(productLine);
    }

    public ProductLine productLine() { return productLine; }
    public long previousCoreSequence() { return previousSequence; }
    public long coreSequence() { return sequence; }
    public long previousProjectionSequence() { return previousSequence; }
    public long projectionSequence() { return sequence; }
    public long beforeRevision() { return beforeRevision; }
    public long afterRevision() { return afterRevision; }
    public long beforeBusinessStateHash() { return beforeBusinessStateHash; }
    public long businessStateHash() { return businessStateHash; }
    public long beforeFundsStateHash() { return beforeFundsStateHash; }
    public long fundsStateHash() { return fundsStateHash; }
    public long laneMask() { return laneMask; }
    public List<AccountLaneOwnerGroup> accountLaneGroups() { return accountLaneGroups; }
    public GlobalOwnerGroup globalOwnerGroup() { return globalOwnerGroup; }
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
    public long sequence() { return sequence; }
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
            for (OrderChange change : group.orders) {
                CoreOrderState order = change.businessAfter();
                if (order != null) orders.add(order);
            }
            for (LiquidationChange change : group.liquidations) {
                if (change.after != null && !change.asset.isBlank()) {
                    liquidations.add(liquidationView(change, registry));
                }
            }
            for (TriggerOrderChange change : group.triggerOrders) {
                if (change.after != null) triggers.add(change.after.view());
            }
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
        for (UserChange userChange : group.users) {
            UserRuntime user = userChange.after;
            if (user == null) continue;
            UserFactBuilder builder = new UserFactBuilder(user);
            ordered.add(builder);
        }
        for (BalanceChange change : group.balances) {
            UserFactBuilder builder = userFactBuilder(ordered, change.key.userId);
            if (builder != null && change.after != null) builder.balances.add(new CoreBalanceView(
                    identities.asset(change.key.assetId), change.after.availableUnits, change.after.lockedUnits));
        }
        for (ReservationChange change : group.reservations) {
            ReservationRuntime value = change.after;
            UserFactBuilder builder = value == null ? null : userFactBuilder(ordered, value.userId());
            if (builder != null) builder.reservations.add(new CoreReservationView(value.orderId(),
                    identities.symbol(value.symbolId()), value.instrumentVersion(), value.kind(),
                    identities.asset(value.assetId()), value.totalReservedUnits(), value.releasedUnits(),
                    value.consumedUnits(), value.orderQuantitySteps()));
        }
        for (PositionChange change : group.positions) {
            PositionRuntime value = change.after;
            UserFactBuilder builder = value == null ? null : userFactBuilder(ordered, value.userId());
            if (builder != null) builder.positions.add(new CorePositionView(identities.symbol(value.symbolId()),
                    identities.asset(value.assetId()), value.marginMode(), value.positionSide(),
                    value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                    value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits()));
        }
        for (LeverageChange change : group.leverages) {
            UserFactBuilder builder = userFactBuilder(ordered, change.key.userId());
            if (builder != null && change.after != null) builder.leverages.add(
                    new CoreLeverageView(change.key.symbol(), change.key.marginMode(), change.after));
        }
        for (UserFactBuilder builder : ordered) result.add(builder.materialize());
    }

    private static UserFactBuilder userFactBuilder(ArrayList<UserFactBuilder> values, long userId) {
        int low = 0;
        int high = values.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            UserFactBuilder candidate = values.get(middle);
            int comparison = Long.compare(candidate.user.userId(), userId);
            if (comparison == 0) return candidate;
            if (comparison < 0) low = middle + 1; else high = middle - 1;
        }
        return null;
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
            for (UserChange change : group.users) if (change.after == null) users.add(change.userId);
            for (BalanceChange change : group.balances) if (change.after == null) {
                balances.add(new CoreExportEvent.UserAssetKey(change.key.userId,
                        identities.asset(change.key.assetId)));
            }
            for (ReservationChange change : group.reservations) if (change.after == null) {
                reservations.add(new CoreExportEvent.UserOrderKey(change.before.userId(), change.orderId));
            }
            for (OrderChange change : group.orders) if (change.after == null) orders.add(change.orderId);
            for (PositionChange change : group.positions) if (change.after == null) {
                positions.add(new CoreExportEvent.UserPositionKey(change.before.userId(),
                        identities.symbol(change.before.symbolId()), change.before.positionSide()));
            }
            for (LeverageChange change : group.leverages) if (change.after == null) {
                leverages.add(new CoreExportEvent.UserLeverageKey(change.key.userId(),
                        change.key.symbol(), change.key.marginMode()));
            }
            for (LiquidationChange change : group.liquidations) {
                if (change.after == null) liquidations.add(change.liquidationId);
            }
            for (AlgoOrderChange change : group.algoOrders) if (change.after == null) algos.add(change.algoOrderId);
            for (TriggerOrderChange change : group.triggerOrders) {
                if (change.after == null) triggers.add(change.triggerOrderId);
            }
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
        ArrayList<String> treasury = new ArrayList<>();
        for (TreasuryAssetChange change : patch.globalOwnerGroup.treasuryAssets) {
            if (change.after == null) treasury.add(identities.asset(change.assetId));
        }
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
        return previousSequence == patch.previousSequence && sequence == patch.sequence
                && beforeRevision == patch.beforeRevision
                && afterRevision == patch.afterRevision && beforeBusinessStateHash == patch.beforeBusinessStateHash
                && businessStateHash == patch.businessStateHash && beforeFundsStateHash == patch.beforeFundsStateHash
                && fundsStateHash == patch.fundsStateHash && laneMask == patch.laneMask
                && productLine == patch.productLine
                && accountLaneGroups.equals(patch.accountLaneGroups)
                && globalOwnerGroup.equals(patch.globalOwnerGroup) && fundsPostings.equals(patch.fundsPostings)
                && matcherTransition.equals(patch.matcherTransition) && matcherEvidence.equals(patch.matcherEvidence)
                && terminalIds.equals(patch.terminalIds) && coreFactValues.equals(patch.coreFactValues)
                && Objects.equals(coreFactMetadata, patch.coreFactMetadata)
                && Objects.equals(identities, patch.identities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productLine, previousSequence, sequence,
                beforeRevision, afterRevision, beforeBusinessStateHash, businessStateHash,
                beforeFundsStateHash, fundsStateHash, laneMask, accountLaneGroups, globalOwnerGroup,
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
                                    List<PositionIdentityValue> positions,
                                    long dictionaryVersion,
                                    IdentityView dictionary) implements IdentityView {
        private static final FactIdentitySlice EMPTY = new FactIdentitySlice(
                List.of(), List.of(), List.of(), List.of(), 0, null);

        public FactIdentitySlice(List<IdentityValue> assets, List<IdentityValue> symbols,
                                 List<ClientIdentityValue> clients,
                                 List<PositionIdentityValue> positions) {
            this(assets, symbols, clients, positions, 0, null);
        }

        public FactIdentitySlice {
            assets = canonicalIdentities(assets, "asset");
            symbols = canonicalIdentities(symbols, "symbol");
            clients = canonicalLongIdentities(clients, "client");
            positions = canonicalLongIdentities(positions, "position");
            if (dictionaryVersion < 0) throw new IllegalArgumentException("invalid identity dictionary version");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FactIdentitySlice slice)) return false;
            return dictionaryVersion == slice.dictionaryVersion
                    && assets.equals(slice.assets) && symbols.equals(slice.symbols)
                    && clients.equals(slice.clients) && positions.equals(slice.positions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assets, symbols, clients, positions, dictionaryVersion);
        }

        @Override public String asset(int assetId) {
            String value = findIdentityOrNull(assets, assetId);
            return value != null ? value : requireDictionary().asset(assetId);
        }
        String assetOrNull(int assetId) {
            String value = findIdentityOrNull(assets, assetId);
            return value != null || dictionary == null ? value : dictionary.asset(assetId);
        }
        @Override public int assetId(String asset) {
            for (IdentityValue value : assets) if (value.value().equals(asset)) return value.id();
            return requireDictionary().assetId(asset);
        }
        @Override public String symbol(int symbolId) {
            String value = findIdentityOrNull(symbols, symbolId);
            return value != null ? value : requireDictionary().symbol(symbolId);
        }
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
            IdentityView mergedDictionary = dictionary != null ? dictionary : other.dictionary;
            if (dictionary != null && other.dictionary != null && dictionary != other.dictionary) {
                throw new IllegalStateException("conflicting patch identity dictionaries");
            }
            return new FactIdentitySlice(mergeSorted(assets, other.assets, "asset"),
                    mergeSorted(symbols, other.symbols, "symbol"),
                    mergeSorted(clients, other.clients, "client"),
                    mergeSorted(positions, other.positions, "position"),
                    Math.max(dictionaryVersion, other.dictionaryVersion), mergedDictionary);
        }

        private static <T extends Comparable<? super T>> List<T> mergeSorted(
                List<T> left, List<T> right, String kind) {
            if (left.isEmpty()) return right;
            if (right.isEmpty()) return left;
            ArrayList<T> merged = new ArrayList<>(Math.addExact(left.size(), right.size()));
            int leftIndex = 0;
            int rightIndex = 0;
            while (leftIndex < left.size() && rightIndex < right.size()) {
                T leftValue = left.get(leftIndex);
                T rightValue = right.get(rightIndex);
                int comparison = leftValue.compareTo(rightValue);
                if (comparison < 0) {
                    merged.add(leftValue);
                    leftIndex++;
                } else if (comparison > 0) {
                    merged.add(rightValue);
                    rightIndex++;
                } else {
                    if (!leftValue.equals(rightValue)) {
                        throw new IllegalStateException("conflicting patch " + kind + " identity");
                    }
                    merged.add(leftValue);
                    leftIndex++;
                    rightIndex++;
                }
            }
            while (leftIndex < left.size()) merged.add(left.get(leftIndex++));
            while (rightIndex < right.size()) merged.add(right.get(rightIndex++));
            return List.copyOf(merged);
        }

        private static FactIdentitySlice capture(List<AccountLaneOwnerGroup> groups, GlobalOwnerGroup global,
                                                 List<FundsPosting> funds,
                                                 RuntimeIdentityRegistry registry) {
            java.util.TreeSet<ClientOrderKey> clientKeys = new java.util.TreeSet<>();
            org.eclipse.collections.impl.set.mutable.primitive.LongHashSet positionKeys =
                    new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
            for (AccountLaneOwnerGroup group : groups) {
                for (PositionChange change : group.positions()) {
                    positionKeys.add(change.positionKey());
                }
                for (RiskSnapshotChange change : group.riskSnapshots()) {
                    positionKeys.add(change.riskKey());
                }
                for (ClientOrderChange change : group.clientOrders()) clientKeys.add(change.key());
            }
            if (registry == null) return EMPTY;
            long[] orderedPositionKeys = positionKeys.toArray();
            java.util.Arrays.sort(orderedPositionKeys);
            ArrayList<ClientIdentityValue> clients = new ArrayList<>(clientKeys.size());
            for (ClientOrderKey key : clientKeys) clients.add(new ClientIdentityValue(
                    key.clientKey(), key.userId(), registry.clientOrderId(key.userId(), key.clientKey())));
            ArrayList<PositionIdentityValue> positions = new ArrayList<>(orderedPositionKeys.length);
            for (long key : orderedPositionKeys) {
                positions.add(new PositionIdentityValue(key, registry.positionIdentity(key)));
            }
            return new FactIdentitySlice(List.of(), List.of(), clients, positions,
                    registry.dictionaryVersion(), registry);
        }

        private static <T extends Comparable<? super T>> List<T> canonicalLongIdentities(List<T> values,
                                                                                          String kind) {
            if (values == null || values.isEmpty()) return List.of();
            boolean ordered = true;
            T previous = Objects.requireNonNull(values.getFirst(), "patch identity");
            for (int index = 1; index < values.size(); index++) {
                T current = Objects.requireNonNull(values.get(index), "patch identity");
                int comparison = previous.compareTo(current);
                if (comparison == 0) throw new IllegalArgumentException("duplicate patch " + kind + " identity");
                if (comparison > 0) ordered = false;
                previous = current;
            }
            if (ordered) return List.copyOf(values);
            ArrayList<T> copy = new ArrayList<>(values);
            copy.sort(null);
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).compareTo(copy.get(index)) == 0) {
                    throw new IllegalArgumentException("duplicate patch " + kind + " identity");
                }
            }
            return List.copyOf(copy);
        }

        private static List<IdentityValue> canonicalIdentities(List<IdentityValue> values, String kind) {
            return canonicalLongIdentities(values, kind);
        }

        private IdentityView requireDictionary() {
            if (dictionary == null) throw new IllegalArgumentException("identity dictionary is unavailable");
            return dictionary;
        }

        private static String findIdentityOrNull(List<IdentityValue> values, int id) {
            int low = 0, high = values.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                IdentityValue value = values.get(middle);
                if (value.id() == id) return value.value();
                if (value.id() < id) low = middle + 1; else high = middle - 1;
            }
            return null;
        }
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
            List<TimerChange> timers) {
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
    }

    public record GlobalOwnerGroup(
            List<MarkPriceChange> markPrices,
            List<RiskScanChange> riskScans,
            List<InstrumentChange> instruments,
            List<TreasuryAssetChange> treasuryAssets,
            List<TreasuryFundingChange> treasuryFunding,
            List<TreasuryLifecycleChange> treasuryLifecycle,
            NextLiquidationIdChange nextLiquidationId,
            RiskScanControlChange riskScanControl) {
        public GlobalOwnerGroup {
            markPrices = List.copyOf(markPrices);
            riskScans = List.copyOf(riskScans);
            instruments = List.copyOf(instruments);
            treasuryAssets = List.copyOf(treasuryAssets);
            treasuryFunding = List.copyOf(treasuryFunding);
            treasuryLifecycle = List.copyOf(treasuryLifecycle);
        }
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
        private final List<AccountLaneOwnerGroup> accountLaneGroups;
        private final GlobalOwnerGroup globalOwnerGroup;
        private final List<FundsPosting> fundsPostings;
        private final RuntimeFundsDelta fundsDelta;
        private final List<MatcherEvidence> matcherEvidence;
        private final TerminalIds terminalIds;

        private PreparedChanges(Builder builder, PrepareMetadata metadata, FactIdentitySlice identities,
                                List<AccountLaneOwnerGroup> accountLaneGroups,
                                GlobalOwnerGroup globalOwnerGroup,
                                List<FundsPosting> fundsPostings, RuntimeFundsDelta fundsDelta,
                                List<MatcherEvidence> matcherEvidence, TerminalIds terminalIds) {
            this.builder = builder;
            this.metadata = metadata;
            this.identities = identities;
            this.accountLaneGroups = accountLaneGroups;
            this.globalOwnerGroup = globalOwnerGroup;
            this.fundsPostings = fundsPostings;
            this.fundsDelta = fundsDelta;
            this.matcherEvidence = matcherEvidence;
            this.terminalIds = terminalIds;
        }

        public ProductLine productLine() { return builder.productLine; }
        public long previousCoreSequence() { return builder.previousSequence; }
        public long coreSequence() { return builder.sequence; }
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
        private ProductLine productLine;
        private long previousSequence;
        private long sequence;
        private boolean sequencesSet;
        private final LaneChanges[] lanes = new LaneChanges[Long.SIZE - 1];
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

        private Builder(ProductLine productLine, long previousSequence, long sequence) {
            this(productLine);
            sequences(previousSequence, sequence);
        }

        private Builder(ProductLine productLine) {
            this.productLine = Objects.requireNonNull(productLine, "product line");
        }

        Builder reset() {
            return reset(productLine);
        }

        Builder reset(ProductLine productLine) {
            for (LaneChanges lane : lanes) if (lane != null) lane.reset();
            global.reset();
            fundsPostings.clear();
            matcherEvidence.clear();
            liquidationAssets.clear();
            previousSequence = 0;
            sequence = 0;
            laneMask = 0;
            terminalOrderIds = List.of();
            terminalLiquidationIds = List.of();
            terminalTriggerOrderIds = List.of();
            matcherTransition = null;
            coreFactValues = CoreFactValues.empty();
            sequencesSet = false;
            coreFactValuesSet = false;
            terminalIdsSet = false;
            sealed = false;
            finalSealed = false;
            activePrepared = null;
            this.productLine = Objects.requireNonNull(productLine, "product line");
            return this;
        }

        Builder sequences(long previousSequence, long sequence) {
            requireOpen();
            if (sequencesSet) throw new IllegalStateException("patch sequences are already set");
            if (productLine == null || previousSequence < 0 || sequence <= 0) {
                throw new IllegalArgumentException("invalid patch sequence metadata");
            }
            if (sequence != Math.incrementExact(previousSequence)) {
                throw new IllegalArgumentException("commit sequence must be contiguous");
            }
            this.previousSequence = previousSequence;
            this.sequence = sequence;
            sequencesSet = true;
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

        public Builder laneMask(long laneMask) {
            requireOpen();
            if (laneMask < 0 || this.laneMask != 0 && this.laneMask != laneMask) {
                throw new IllegalArgumentException("invalid or conflicting lane mask");
            }
            this.laneMask = laneMask;
            return this;
        }

        long laneMask() {
            requireOpen();
            return laneMask;
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
                    prepared.accountLaneGroups, prepared.globalOwnerGroup,
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
            long changedLaneMask = changedLaneMask();
            if (laneMask != changedLaneMask) {
                throw new IllegalArgumentException("commit lane mask must equal changed lane mask"
                        + " changedMask=" + changedLaneMask + " commitMask=" + laneMask);
            }
            if (laneMask != metadata.laneMask()) throw new IllegalArgumentException("lane mask mismatch");
            ArrayList<AccountLaneOwnerGroup> groups = new ArrayList<>(Long.bitCount(laneMask));
            for (int laneId = 0; laneId < lanes.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) groups.add(lanes[laneId].seal(laneId));
            }

            GlobalOwnerGroup sealedGlobal = global.seal();
            ArrayList<FundsPosting> derivedPostings = new ArrayList<>(fundsPostings);
            deriveFundsPostings(groups, sealedGlobal, derivedPostings);
            RuntimeFundsDelta primitiveFunds = RuntimeFundsDelta.fromPatchPostings(derivedPostings);
            primitiveFunds.requireConserved(metadata.externalAdjustment());
            List<FundsPosting> canonicalFunds = primitiveFunds.postings();
            List<MatcherEvidence> canonicalEvidence = canonicalDistinct(
                    matcherEvidence, "duplicate matcher evidence");
            TerminalIds terminals = terminalIdsSet
                    ? new TerminalIds(terminalOrderIds, terminalLiquidationIds, terminalTriggerOrderIds)
                    : terminalIds(groups);
            FactIdentitySlice identitySlice = FactIdentitySlice.capture(groups, sealedGlobal,
                    canonicalFunds, identities);
            PreparedChanges prepared = new PreparedChanges(this, prepareMetadata, identitySlice,
                    List.copyOf(groups), sealedGlobal, canonicalFunds,
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
                for (OrderChange change : group.orders) {
                    if (change.after != null && change.after.status().terminal()) {
                        orders.add(change.orderId);
                    }
                }
                for (LiquidationChange change : group.liquidations) {
                    if (change.after != null
                            && (change.after.status() == CoreLiquidationState.Status.CANCELED
                            || change.after.status() == CoreLiquidationState.Status.COMPLETED
                            && change.after.deficitUnits() == 0)) {
                        liquidations.add(change.liquidationId);
                    }
                }
                for (TriggerOrderChange change : group.triggerOrders) {
                    if (change.after != null && !change.after.status().open()) {
                        triggers.add(change.triggerOrderId);
                    }
                }
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
            for (int laneId = 0; laneId < lanes.length; laneId++) {
                if (lanes[laneId] != null && lanes[laneId].hasChanges()) changed.add(laneId);
            }
            return java.util.Set.copyOf(changed);
        }

        long changedLaneMask() {
            long mask = 0;
            for (int laneId = 0; laneId < lanes.length; laneId++) {
                if (lanes[laneId] != null && lanes[laneId].hasChanges()) mask |= 1L << laneId;
            }
            return mask;
        }

        private LaneChanges lane(int laneId) {
            requireOpen();
            if (laneId < 0 || laneId >= Long.SIZE - 1) throw new IllegalArgumentException("invalid lane id");
            LaneChanges changes = lanes[laneId];
            if (changes == null) {
                changes = new LaneChanges();
                lanes[laneId] = changes;
            }
            return changes;
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

        private void reset() {
            users.reset();
            balances.reset();
            reservations.reset();
            orders.reset();
            positions.reset();
            liquidations.reset();
            riskSnapshots.reset();
            leverages.reset();
            algoOrders.reset();
            triggerOrders.reset();
            clientOrders.reset();
            timers.reset();
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

        private void reset() {
            markPrices.reset();
            riskScans.reset();
            instruments.reset();
            treasuryAssets.reset();
            treasuryFunding.reset();
            treasuryLifecycle.reset();
            nextLiquidationId.reset();
            riskScanControl.reset();
        }

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
        private Object[] keys = new Object[8];
        private Object[] values = new Object[8];
        private int[] stamps = new int[8];
        private int[] touchedSlots = new int[8];
        private int generation = 1;
        private int size;
        private int changedCount;

        private void captureBefore(K key, V before) {
            if (key == null) throw new IllegalArgumentException("invalid typed patch key");
            ensureCapacity();
            int slot = findSlot(key);
            if (stamps[slot] != generation) insert(slot, key, new BeforeAfter<>(before, before));
        }

        private V before(K key) {
            BeforeAfter<V> captured = change(key);
            return captured == null ? null : captured.before;
        }

        private boolean contains(K key) {
            return change(key) != null;
        }

        private boolean hasChanges() {
            return changedCount != 0;
        }

        private void reset() {
            size = 0;
            changedCount = 0;
            if (generation == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamps, 0);
                generation = 1;
            } else {
                generation++;
            }
        }

        private void record(K key, V before, V after) {
            if (key == null || before == null && after == null) {
                throw new IllegalArgumentException("invalid typed patch change");
            }
            ensureCapacity();
            int slot = findSlot(key);
            BeforeAfter<V> existing = stamps[slot] == generation ? value(slot) : null;
            if (existing == null) {
                insert(slot, key, new BeforeAfter<>(before, after));
                if (!Objects.equals(before, after)) changedCount++;
                return;
            }
            if (!Objects.equals(before, existing.before) && !Objects.equals(before, existing.after)) {
                throw new IllegalArgumentException("conflicting before-value for patch key " + key);
            }
            boolean changedBefore = !Objects.equals(existing.before, existing.after);
            existing.after = after;
            boolean changedAfter = !Objects.equals(existing.before, existing.after);
            if (changedBefore != changedAfter) changedCount += changedAfter ? 1 : -1;
        }

        private <R> List<R> seal(BiFunction<K, BeforeAfter<V>, R> materializer) {
            if (changedCount == 0) return List.of();
            ArrayList<R> result = new ArrayList<>(changedCount);
            int[] ordered = new int[changedCount];
            int index = 0;
            for (int touchedIndex = 0; touchedIndex < size; touchedIndex++) {
                int slot = touchedSlots[touchedIndex];
                BeforeAfter<V> change = value(slot);
                if (!Objects.equals(change.before, change.after)) ordered[index++] = slot;
            }
            sort(ordered, 0, ordered.length - 1);
            for (int slot : ordered) {
                result.add(materializer.apply(key(slot), value(slot)));
            }
            return java.util.Collections.unmodifiableList(result);
        }

        private <R> R single(java.util.function.Function<BeforeAfter<V>, R> materializer) {
            if (changedCount == 0) return null;
            for (int index = 0; index < size; index++) {
                BeforeAfter<V> change = value(touchedSlots[index]);
                if (!Objects.equals(change.before, change.after)) return materializer.apply(change);
            }
            throw new IllegalStateException("changed patch entry is missing");
        }

        private BeforeAfter<V> change(K key) {
            if (key == null) return null;
            int slot = findSlot(key);
            return stamps[slot] == generation ? value(slot) : null;
        }

        private void ensureCapacity() {
            if ((size + 1) * 2 <= keys.length) return;
            Object[] previousKeys = keys;
            Object[] previousValues = values;
            int[] previousTouched = touchedSlots;
            int previousSize = size;
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = new Object[capacity];
            values = new Object[capacity];
            stamps = new int[capacity];
            touchedSlots = new int[capacity];
            size = 0;
            for (int index = 0; index < previousSize; index++) {
                int previousSlot = previousTouched[index];
                @SuppressWarnings("unchecked") K key = (K) previousKeys[previousSlot];
                insert(findSlot(key), key, previousValues[previousSlot]);
            }
        }

        private int findSlot(Object key) {
            int hash = key.hashCode();
            hash ^= hash >>> 16;
            int slot = hash & (keys.length - 1);
            while (stamps[slot] == generation && !keys[slot].equals(key)) {
                slot = slot + 1 & (keys.length - 1);
            }
            return slot;
        }

        private void insert(int slot, K key, Object value) {
            stamps[slot] = generation;
            keys[slot] = key;
            values[slot] = value;
            touchedSlots[size++] = slot;
        }

        private void sort(int[] slots, int low, int high) {
            int left = low;
            int right = high;
            K pivot = key(slots[(low + high) >>> 1]);
            while (left <= right) {
                while (key(slots[left]).compareTo(pivot) < 0) left++;
                while (key(slots[right]).compareTo(pivot) > 0) right--;
                if (left <= right) {
                    int swap = slots[left];
                    slots[left++] = slots[right];
                    slots[right--] = swap;
                }
            }
            if (low < right) sort(slots, low, right);
            if (left < high) sort(slots, left, high);
        }

        @SuppressWarnings("unchecked")
        private K key(int slot) {
            return (K) keys[slot];
        }

        @SuppressWarnings("unchecked")
        private BeforeAfter<V> value(int slot) {
            return (BeforeAfter<V>) values[slot];
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
