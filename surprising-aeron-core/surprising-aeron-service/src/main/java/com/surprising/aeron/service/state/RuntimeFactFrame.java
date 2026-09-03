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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

interface RuntimeFactView {
    ProductLine productLine();
    long previousCoreSequence();
    long coreSequence();
    long beforeRevision();
    long beforeFundsStateHash();
    List<RuntimeFactFrame.AccountLaneOwnerGroup> accountLaneGroups();
    RuntimeFactFrame.GlobalOwnerGroup globalOwnerGroup();
    RuntimeFactFrame.FactIdentitySlice identities();
}

public final class RuntimeFactFrame implements RuntimeFactView {

    interface ChangeConsumer {
        default void order(long orderId, OrderRuntime before, OrderRuntime after) {}
        default void position(long positionKey, PositionRuntime before, PositionRuntime after) {}
        default void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {}
        default void riskSnapshot(long riskKey, RiskSnapshotRuntime before, RiskSnapshotRuntime after) {}
        default void algoOrder(long algoOrderId, CoreAlgoOrderState before, CoreAlgoOrderState after) {}
        default void triggerOrder(long triggerOrderId, CoreTriggerOrderState before,
                                  CoreTriggerOrderState after) {}
        default void timer(CoreCancelAllAfterKey key, CoreCancelAllAfterState before,
                           CoreCancelAllAfterState after) {}
    }

    public interface RetentionConsumer {
        void order(OrderRuntime value);
        void liquidation(LiquidationRuntime value);
        void algoOrder(CoreAlgoOrderState value);
        void triggerOrder(CoreTriggerOrderState value);
    }

    public interface IdentityReleaseConsumer {
        void clientOrder(long userId, long clientKey, Long afterOrderId);
        void position(long positionKey, PositionRuntime after);
        void riskSnapshot(long riskKey, RiskSnapshotRuntime after);
    }

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
    private final int coreFactItemCount;
    private final long estimatedCoreFactBytes;

    private RuntimeFactFrame(Builder builder, SealMetadata metadata,
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
        coreFactItemCount = countCoreFactItems(accountLaneGroups, globalOwnerGroup, fundsPostings,
                matcherEvidence, terminalIds);
        estimatedCoreFactBytes = Math.addExact(4_096L,
                Math.multiplyExact((long) coreFactItemCount, 2_048L));
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
        ArrayList<Long> ids = new ArrayList<>();
        acceptChangedUserIds(userId -> {
            for (long existing : ids) if (existing == userId) return;
            ids.add(userId);
        });
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

    void visitTerminalValues(RetentionConsumer consumer) {
        Objects.requireNonNull(consumer, "retention consumer");
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            group.orders.forEach(change -> consumer.order(change.after));
            group.liquidations.forEach(change -> consumer.liquidation(change.after));
            group.algoOrders.forEach(change -> consumer.algoOrder(change.after));
            group.triggerOrders.forEach(change -> consumer.triggerOrder(change.after));
        }
    }

    void visitIdentityReleases(IdentityReleaseConsumer consumer) {
        Objects.requireNonNull(consumer, "identity release consumer");
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            group.clientOrders.forEach(change ->
                    consumer.clientOrder(change.key.userId(), change.key.clientKey(), change.afterOrderId));
            group.positions.forEach(change -> consumer.position(change.positionKey, change.after));
            group.riskSnapshots.forEach(change -> consumer.riskSnapshot(change.riskKey, change.after));
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
        return coreFactItemCount;
    }

    private static int countCoreFactItems(List<AccountLaneOwnerGroup> accountLaneGroups,
                                          GlobalOwnerGroup globalOwnerGroup,
                                          List<FundsPosting> fundsPostings,
                                          List<MatcherEvidence> matcherEvidence,
                                          TerminalIds terminalIds) {
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
        return estimatedCoreFactBytes;
    }

    public CoreFactFragment materializeCoreFactFragment() {
        FactIdentitySlice registry = identities();
        ArrayList<CoreUserStateView> users = new ArrayList<>();
        ArrayList<CoreOrderStateView> orders = new ArrayList<>();
        ArrayList<CoreLiquidationView> liquidations = null;
        ArrayList<CoreTriggerOrderStateView> triggers = null;
        for (AccountLaneOwnerGroup group : accountLaneGroups) {
            appendUsers(users, group, registry);
            for (OrderChange change : group.orders) {
                CoreOrderStateView order = change.after() == null ? null
                        : exportRuntimeOrderView(change.after(), registry);
                if (order != null) orders.add(order);
            }
            for (LiquidationChange change : group.liquidations) {
                if (change.after != null && !change.asset.isBlank()) {
                    if (liquidations == null) liquidations = new ArrayList<>();
                    liquidations.add(liquidationView(change, registry));
                }
            }
            for (TriggerOrderChange change : group.triggerOrders) {
                if (change.after != null) {
                    if (triggers == null) triggers = new ArrayList<>();
                    triggers.add(change.after.view());
                }
            }
        }
        ArrayList<CoreTreasuryAssetView> treasury = null;
        for (TreasuryAssetChange change : globalOwnerGroup.treasuryAssets) {
            TreasuryAssetValue value = change.after;
            if (value == null) continue;
            if (treasury == null) treasury = new ArrayList<>();
            treasury.add(new CoreTreasuryAssetView(registry.asset(change.assetId), treasuryFee(value),
                    treasuryInsurance(value), treasuryDeficit(value), treasuryLiquidationFee(value),
                    treasuryFundingResidual(value), treasuryRoundingResidual(value), treasuryClearingPnl(value)));
        }
        return new CoreFactFragment(users, orders, listOrEmpty(liquidations), listOrEmpty(treasury),
                listOrEmpty(triggers), matcherEvidence,
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
        for (UserChange userChange : group.users) {
            UserRuntime user = userChange.after;
            if (user == null) continue;
            ArrayList<CoreBalanceView> balances = null;
            for (BalanceChange change : group.balances) {
                if (change.key.userId != user.userId() || change.after == null) continue;
                if (balances == null) balances = new ArrayList<>();
                balances.add(new CoreBalanceView(identities.asset(change.key.assetId),
                        change.after.availableUnits, change.after.lockedUnits));
            }
            ArrayList<CoreReservationView> reservations = null;
            for (ReservationChange change : group.reservations) {
                ReservationRuntime value = change.after;
                if (value == null || value.userId() != user.userId()) continue;
                if (reservations == null) reservations = new ArrayList<>();
                reservations.add(new CoreReservationView(value.orderId(), identities.symbol(value.symbolId()),
                        value.instrumentVersion(), value.kind(), identities.asset(value.assetId()),
                        value.totalReservedUnits(), value.releasedUnits(), value.consumedUnits(),
                        value.orderQuantitySteps()));
            }
            ArrayList<CorePositionView> positions = null;
            for (PositionChange change : group.positions) {
                PositionRuntime value = change.after;
                if (value == null || value.userId() != user.userId()) continue;
                if (positions == null) positions = new ArrayList<>();
                positions.add(new CorePositionView(identities.symbol(value.symbolId()),
                        identities.asset(value.assetId()), value.marginMode(), value.positionSide(),
                        value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                        value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits()));
            }
            ArrayList<CoreLeverageView> leverages = null;
            for (LeverageChange change : group.leverages) {
                if (change.key.userId() != user.userId() || change.after == null) continue;
                if (leverages == null) leverages = new ArrayList<>();
                leverages.add(new CoreLeverageView(change.key.symbol(), change.key.marginMode(), change.after));
            }
            result.add(new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                    user.positionMode(), listOrEmpty(balances), listOrEmpty(reservations),
                    listOrEmpty(positions), listOrEmpty(leverages)));
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

    public static CoreOrderStateView exportRuntimeOrderView(OrderRuntime order, IdentityView identities) {
        if (order == null || identities == null) throw new IllegalArgumentException("runtime order is required");
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.symbol(order.symbolId()), order.instrumentVersion(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(),
                order.timeInForce(), order.postOnly(), order.clientOrderId(), order.commandId(),
                order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.cumulativeFeeUnits(),
                order.createdAtEpochMillis(), order.updatedAtEpochMillis(), order.clusterPosition(),
                order.status().name(), order.revision());
    }

    private static CoreExportEvent.Tombstones tombstones(RuntimeFactFrame patch,
                                                          IdentityView identities) {
        ArrayList<Long> users = null;
        ArrayList<CoreExportEvent.UserAssetKey> balances = null;
        ArrayList<CoreExportEvent.UserOrderKey> reservations = null;
        ArrayList<Long> orders = null;
        ArrayList<CoreExportEvent.UserPositionKey> positions = null;
        ArrayList<CoreExportEvent.UserLeverageKey> leverages = null;
        ArrayList<Long> liquidations = null;
        ArrayList<Long> algos = null;
        ArrayList<Long> triggers = null;
        for (AccountLaneOwnerGroup group : patch.accountLaneGroups) {
            for (UserChange change : group.users) if (change.after == null) {
                if (users == null) users = new ArrayList<>();
                users.add(change.userId);
            }
            for (BalanceChange change : group.balances) if (change.after == null) {
                if (balances == null) balances = new ArrayList<>();
                balances.add(new CoreExportEvent.UserAssetKey(change.key.userId,
                        identities.asset(change.key.assetId)));
            }
            for (ReservationChange change : group.reservations) if (change.after == null) {
                if (reservations == null) reservations = new ArrayList<>();
                reservations.add(new CoreExportEvent.UserOrderKey(change.before.userId(), change.orderId));
            }
            for (OrderChange change : group.orders) if (change.after == null) {
                if (orders == null) orders = new ArrayList<>();
                orders.add(change.orderId);
            }
            for (PositionChange change : group.positions) if (change.after == null) {
                if (positions == null) positions = new ArrayList<>();
                positions.add(new CoreExportEvent.UserPositionKey(change.before.userId(),
                        identities.symbol(change.before.symbolId()), change.before.positionSide()));
            }
            for (LeverageChange change : group.leverages) if (change.after == null) {
                if (leverages == null) leverages = new ArrayList<>();
                leverages.add(new CoreExportEvent.UserLeverageKey(change.key.userId(),
                        change.key.symbol(), change.key.marginMode()));
            }
            for (LiquidationChange change : group.liquidations) {
                if (change.after == null) {
                    if (liquidations == null) liquidations = new ArrayList<>();
                    liquidations.add(change.liquidationId);
                }
            }
            for (AlgoOrderChange change : group.algoOrders) if (change.after == null) {
                if (algos == null) algos = new ArrayList<>();
                algos.add(change.algoOrderId);
            }
            for (TriggerOrderChange change : group.triggerOrders) {
                if (change.after == null) {
                    if (triggers == null) triggers = new ArrayList<>();
                    triggers.add(change.triggerOrderId);
                }
            }
        }
        ArrayList<String> treasury = null;
        for (TreasuryAssetChange change : patch.globalOwnerGroup.treasuryAssets) {
            if (change.after == null) {
                if (treasury == null) treasury = new ArrayList<>();
                treasury.add(identities.asset(change.assetId));
            }
        }
        if (users == null && balances == null && reservations == null && orders == null
                && positions == null && leverages == null && liquidations == null && algos == null
                && triggers == null && treasury == null) {
            return CoreExportEvent.Tombstones.empty();
        }
        return new CoreExportEvent.Tombstones(listOrEmpty(users), listOrEmpty(balances),
                listOrEmpty(reservations), listOrEmpty(orders), listOrEmpty(positions),
                listOrEmpty(leverages), listOrEmpty(liquidations), listOrEmpty(algos),
                listOrEmpty(triggers), listOrEmpty(treasury));
    }

    private static <T> List<T> listOrEmpty(ArrayList<T> values) {
        return values == null ? List.of() : values;
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
        if (!(other instanceof RuntimeFactFrame patch)) return false;
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
            return requireDictionary().clientOrderId(userId, clientKey);
        }
        @Override public RuntimeIdentityRegistry.PositionIdentity positionIdentity(long positionKey) {
            for (PositionIdentityValue value : positions) if (value.key() == positionKey) return value.identity();
            return requireDictionary().positionIdentity(positionKey);
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
            return new FactIdentitySlice(mergeFirstTouch(assets, other.assets, "asset"),
                    mergeFirstTouch(symbols, other.symbols, "symbol"),
                    mergeFirstTouch(clients, other.clients, "client"),
                    mergeFirstTouch(positions, other.positions, "position"),
                    Math.max(dictionaryVersion, other.dictionaryVersion), mergedDictionary);
        }

        private static <T extends Comparable<? super T>> List<T> mergeFirstTouch(
                List<T> left, List<T> right, String kind) {
            if (left.isEmpty()) return right;
            if (right.isEmpty()) return left;
            ArrayList<T> merged = new ArrayList<>(Math.addExact(left.size(), right.size()));
            merged.addAll(left);
            for (T candidate : right) {
                boolean duplicate = false;
                for (T existing : merged) {
                    if (existing.compareTo(candidate) != 0) continue;
                    if (!existing.equals(candidate)) {
                        throw new IllegalStateException("conflicting patch " + kind + " identity");
                    }
                    duplicate = true;
                    break;
                }
                if (!duplicate) merged.add(candidate);
            }
            return List.copyOf(merged);
        }

        private static FactIdentitySlice capture(List<AccountLaneOwnerGroup> groups,
                                                 RuntimeIdentityRegistry registry) {
            if (registry == null) return EMPTY;
            ArrayList<ClientIdentityValue> clients = null;
            ArrayList<PositionIdentityValue> positions = null;
            for (AccountLaneOwnerGroup group : groups) {
                for (PositionChange change : group.positions()) {
                    if (change.after() == null) {
                        if (positions == null) positions = new ArrayList<>();
                        positions.add(new PositionIdentityValue(
                                change.positionKey(), registry.positionIdentity(change.positionKey())));
                    }
                }
                for (ClientOrderChange change : group.clientOrders()) {
                    if (change.afterOrderId() == null) {
                        if (clients == null) clients = new ArrayList<>();
                        ClientOrderKey key = change.key();
                        clients.add(new ClientIdentityValue(key.clientKey(), key.userId(),
                                registry.clientOrderId(key.userId(), key.clientKey())));
                    }
                }
            }
            if (clients == null && positions == null) {
                return registry.liveFactIdentitySlice();
            }
            return new FactIdentitySlice(List.of(), List.of(),
                    clients == null ? List.of() : clients,
                    positions == null ? List.of() : positions,
                    registry.dictionaryVersion(), registry);
        }

        private static <T extends Comparable<? super T>> List<T> canonicalLongIdentities(List<T> values,
                                                                                          String kind) {
            if (values == null || values.isEmpty()) return List.of();
            for (int index = 0; index < values.size(); index++) {
                T current = Objects.requireNonNull(values.get(index), "patch identity");
                for (int previous = 0; previous < index; previous++) {
                    if (values.get(previous).compareTo(current) == 0) {
                        throw new IllegalArgumentException("duplicate patch " + kind + " identity");
                    }
                }
            }
            return List.copyOf(values);
        }

        private static List<IdentityValue> canonicalIdentities(List<IdentityValue> values, String kind) {
            return canonicalLongIdentities(values, kind);
        }

        private IdentityView requireDictionary() {
            if (dictionary == null) throw new IllegalArgumentException("identity dictionary is unavailable");
            return dictionary;
        }

        private static String findIdentityOrNull(List<IdentityValue> values, int id) {
            for (IdentityValue value : values) if (value.id() == id) return value.value();
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
    public record OrderChange(long orderId, OrderRuntime before, OrderRuntime after) {
        public OrderChange {
            requireChange(orderId > 0, before, after, "order");
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
                               long units) {
        public FundsPosting {
            if (assetId < 0 || ownerKind == null || subledger == null || units == 0) {
                throw new IllegalArgumentException("invalid patch funds posting");
            }
        }
    }

    public record MatcherEvidence(long matcherSequence, int matcherShardId, long makerOrderId,
                                  long takerOrderId, long quantitySteps, long priceTicks) {
        public MatcherEvidence {
            if (matcherSequence <= 0 || matcherShardId < 0 || makerOrderId <= 0 || takerOrderId <= 0
                    || quantitySteps <= 0 || priceTicks <= 0) {
                throw new IllegalArgumentException("invalid matcher evidence");
            }
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
                                   List<CoreOrderStateView> changedOrders,
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

    public static final class PreparedChanges implements RuntimeFactView {
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
        private Builder nextFree;
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

        Builder nextFree() { return nextFree; }
        void nextFree(Builder nextFree) { this.nextFree = nextFree; }

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

        long previousSequence() { return previousSequence; }
        long sequence() { return sequence; }

        int coreFactItemCount() {
            int count = Math.addExact(fundsPostings.size(), matcherEvidence.size());
            count = Math.addExact(count, terminalOrderIds.size());
            count = Math.addExact(count, terminalLiquidationIds.size());
            count = Math.addExact(count, terminalTriggerOrderIds.size());
            for (LaneChanges lane : lanes) {
                if (lane != null) count = Math.addExact(count, lane.coreFactItemCount());
            }
            return Math.addExact(count, global.treasuryAssets.changedCount());
        }

        RuntimeFundsDelta materializeFundsDelta(boolean externalAdjustment) {
            ArrayList<FundsPosting> derived = new ArrayList<>(fundsPostings);
            for (LaneChanges lane : lanes) {
                if (lane == null) continue;
                lane.balances.forEachChanged((userId, assetId, before, after) -> {
                    addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER,
                            userId, com.surprising.aeron.service.state.FundsPosting.Subledger.AVAILABLE,
                            Math.subtractExact(available(after), available(before)));
                    addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.USER,
                            userId, com.surprising.aeron.service.state.FundsPosting.Subledger.LOCKED,
                            Math.subtractExact(locked(after), locked(before)));
                });
            }
            global.treasuryAssets.forEachChanged((assetId, before, after) -> {
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.FEE,
                        Math.subtractExact(treasuryFee(after), treasuryFee(before)));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.INSURANCE,
                        Math.subtractExact(treasuryInsurance(after), treasuryInsurance(before)));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.DEFICIT,
                        Math.negateExact(Math.subtractExact(treasuryDeficit(after), treasuryDeficit(before))));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.LIQUIDATION_FEE,
                        Math.subtractExact(treasuryLiquidationFee(after), treasuryLiquidationFee(before)));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.FUNDING_RESIDUAL,
                        Math.subtractExact(treasuryFundingResidual(after), treasuryFundingResidual(before)));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.ROUNDING_RESIDUAL,
                        Math.subtractExact(treasuryRoundingResidual(after), treasuryRoundingResidual(before)));
                addPosting(derived, assetId, com.surprising.aeron.service.state.FundsPosting.OwnerKind.TREASURY, 0,
                        com.surprising.aeron.service.state.FundsPosting.Subledger.CLEARING_PNL,
                        Math.subtractExact(treasuryClearingPnl(after), treasuryClearingPnl(before)));
            });
            RuntimeFundsDelta delta = RuntimeFundsDelta.fromPatchPostings(derived);
            delta.requireConserved(externalAdjustment);
            return delta;
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
            lane(laneId).balances.record(userId, assetId, before, after);
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

        public Builder recordOrder(int laneId, OrderRuntime before, OrderRuntime after) {
            requireProductLine(before);
            requireProductLine(after);
            long key = before != null ? before.orderId() : requireNonNull(after, "order").orderId();
            requireEntityId(key, before == null ? key : before.orderId(), after == null ? key : after.orderId(),
                    "order");
            lane(laneId).orders.record(key, before, after);
            return this;
        }

        /** Typed order snapshots are deliberately ignored and never retained on the hot path. */
        public Builder recordOrder(int laneId, OrderRuntime before, OrderRuntime after,
                                   CoreOrderState ignoredBefore, CoreOrderState ignoredAfter) {
            return recordOrder(laneId, before, after);
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

        public RuntimeFactFrame seal(SealMetadata metadata) {
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

        public RuntimeFactFrame seal(PreparedChanges prepared, long businessStateHash, long fundsStateHash) {
            if (prepared == null || prepared.builder != this || prepared != activePrepared) {
                throw new IllegalArgumentException("prepared changes belong to a different builder");
            }
            if (finalSealed) throw new IllegalStateException("patch builder is already sealed");
            PrepareMetadata metadata = prepared.metadata;
            SealMetadata sealedMetadata = new SealMetadata(metadata.beforeRevision(), metadata.afterRevision(),
                    metadata.beforeBusinessStateHash(), businessStateHash,
                    metadata.beforeFundsStateHash(), fundsStateHash, metadata.laneMask(),
                    metadata.coreFactMetadata(), metadata.externalAdjustment());
            RuntimeFactFrame patch = new RuntimeFactFrame(this, sealedMetadata, prepared.identities,
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
            FactIdentitySlice identitySlice = FactIdentitySlice.capture(groups, identities);
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

        void visitChangedIndexes(ChangeConsumer consumer) {
            Objects.requireNonNull(consumer, "change consumer");
            for (LaneChanges lane : lanes) {
                if (lane == null || !lane.hasChanges()) continue;
                lane.orders.forEachChanged(consumer::order);
                lane.positions.forEachChanged(consumer::position);
                lane.liquidations.forEachChanged(consumer::liquidation);
                lane.riskSnapshots.forEachChanged(consumer::riskSnapshot);
                lane.algoOrders.forEachChanged(consumer::algoOrder);
                lane.triggerOrders.forEachChanged(consumer::triggerOrder);
                lane.timers.forEachChanged(consumer::timer);
            }
        }

        void visitTerminalValues(RetentionConsumer consumer) {
            Objects.requireNonNull(consumer, "retention consumer");
            for (LaneChanges lane : lanes) {
                if (lane == null || !lane.hasChanges()) continue;
                lane.orders.forEachChanged((key, before, after) -> consumer.order(after));
                lane.liquidations.forEachChanged((key, before, after) -> consumer.liquidation(after));
                lane.algoOrders.forEachChanged((key, before, after) -> consumer.algoOrder(after));
                lane.triggerOrders.forEachChanged((key, before, after) -> consumer.triggerOrder(after));
            }
        }

        void visitIdentityReleases(IdentityReleaseConsumer consumer) {
            Objects.requireNonNull(consumer, "identity release consumer");
            for (LaneChanges lane : lanes) {
                if (lane == null || !lane.hasChanges()) continue;
                lane.clientOrders.forEachChanged((key, before, after) ->
                        consumer.clientOrder(key.userId(), key.clientKey(), after));
                lane.positions.forEachChanged((key, before, after) -> consumer.position(key, after));
                lane.riskSnapshots.forEachChanged((key, before, after) -> consumer.riskSnapshot(key, after));
            }
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
        private final LongChanges<UserValue> users = new LongChanges<>();
        private final BalanceChanges balances = new BalanceChanges();
        private final LongChanges<ReservationValue> reservations = new LongChanges<>();
        private final LongChanges<OrderRuntime> orders = new LongChanges<>();
        private final LongChanges<PositionRuntime> positions = new LongChanges<>();
        private final LongChanges<LiquidationRuntime> liquidations = new LongChanges<>();
        private final LongChanges<RiskSnapshotRuntime> riskSnapshots = new LongChanges<>();
        private final Changes<CoreLeverageKey, Long> leverages = new Changes<>();
        private final LongChanges<CoreAlgoOrderState> algoOrders = new LongChanges<>();
        private final LongChanges<CoreTriggerOrderState> triggerOrders = new LongChanges<>();
        private final Changes<ClientOrderKey, Long> clientOrders = new Changes<>();
        private final Changes<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new Changes<>();

        private boolean hasChanges() {
            return users.hasChanges() || balances.hasChanges() || reservations.hasChanges()
                    || orders.hasChanges() || positions.hasChanges() || liquidations.hasChanges()
                    || riskSnapshots.hasChanges() || leverages.hasChanges() || algoOrders.hasChanges()
                    || triggerOrders.hasChanges() || clientOrders.hasChanges() || timers.hasChanges();
        }

        private int coreFactItemCount() {
            int count = users.changedCount();
            count = Math.addExact(count, balances.changedCount());
            count = Math.addExact(count, reservations.changedCount());
            count = Math.addExact(count, orders.changedCount());
            count = Math.addExact(count, positions.changedCount());
            count = Math.addExact(count, liquidations.changedCount());
            count = Math.addExact(count, leverages.changedCount());
            count = Math.addExact(count, algoOrders.changedCount());
            return Math.addExact(count, triggerOrders.changedCount());
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
                    users.seal((key, before, after) -> new UserChange(key,
                            before == null ? null : before.value(),
                            after == null ? null : after.value(),
                            after == null ? 0 : after.pendingReservationCount())),
                    balances.seal(),
                    reservations.seal((key, before, after) -> new ReservationChange(key,
                            before == null ? null : before.value(),
                            after == null ? null : after.value(),
                            before != null && before.pending(),
                            after != null && after.pending())),
                    orders.seal(OrderChange::new),
                    positions.seal((key, before, after) -> new PositionChange(key, before, after)),
                    liquidations.seal((key, before, after) -> new LiquidationChange(key, before, after)),
                    riskSnapshots.seal((key, before, after) -> new RiskSnapshotChange(key, before, after)),
                    leverages.seal((key, before, after) -> new LeverageChange(key, before, after)),
                    algoOrders.seal((key, before, after) -> new AlgoOrderChange(key, before, after)),
                    triggerOrders.seal((key, before, after) -> new TriggerOrderChange(key, before, after)),
                    clientOrders.seal((key, before, after) -> new ClientOrderChange(key, before, after)),
                    timers.seal((key, before, after) -> new TimerChange(key, before, after)));
        }
    }

    private static final class GlobalChanges {
        private final IntChanges<MarkPriceRuntime> markPrices = new IntChanges<>();
        private final IntChanges<RiskScanRuntime> riskScans = new IntChanges<>();
        private final Changes<String, CoreInstrumentState> instruments = new Changes<>();
        private final IntChanges<TreasuryAssetValue> treasuryAssets = new IntChanges<>();
        private final IntChanges<TreasuryFundingValue> treasuryFunding = new IntChanges<>();
        private final IntChanges<TreasuryLifecycleValue> treasuryLifecycle = new IntChanges<>();
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
                    markPrices.seal(MarkPriceChange::new),
                    riskScans.seal(RiskScanChange::new),
                    instruments.seal((key, before, after) -> new InstrumentChange(key, before, after)),
                    treasuryAssets.seal(TreasuryAssetChange::new),
                    treasuryFunding.seal(TreasuryFundingChange::new),
                    treasuryLifecycle.seal(TreasuryLifecycleChange::new),
                    nextLiquidationId.single(NextLiquidationIdChange::new),
                    riskScanControl.single(RiskScanControlChange::new));
        }
    }

    @FunctionalInterface
    private interface LongChangeFunction<V, R> {
        R apply(long key, V before, V after);
    }

    @FunctionalInterface
    private interface LongChangeConsumer<V> {
        void accept(long key, V before, V after);
    }

    @FunctionalInterface
    private interface IntChangeFunction<V, R> {
        R apply(int key, V before, V after);
    }

    @FunctionalInterface
    private interface IntChangeConsumer<V> {
        void accept(int key, V before, V after);
    }

    @FunctionalInterface
    private interface BalanceChangeConsumer {
        void accept(long userId, int assetId, UserBalance before, UserBalance after);
    }

    @FunctionalInterface
    private interface ChangeFunction<K, V, R> {
        R apply(K key, V before, V after);
    }

    @FunctionalInterface
    private interface ChangeConsumer3<K, V> {
        void accept(K key, V before, V after);
    }

    /** Primitive user/asset keys avoid allocating a BalanceKey while a command is still mutable. */
    private static final class BalanceChanges {
        private long[] userIds = new long[8];
        private int[] assetIds = new int[8];
        private UserBalance[] beforeValues = new UserBalance[8];
        private UserBalance[] afterValues = new UserBalance[8];
        private int size;
        private int changedCount;

        private boolean hasChanges() { return changedCount != 0; }
        private int changedCount() { return changedCount; }

        private void reset() {
            for (int index = 0; index < size; index++) {
                beforeValues[index] = null;
                afterValues[index] = null;
            }
            size = 0;
            changedCount = 0;
        }

        private void record(long userId, int assetId, UserBalance before, UserBalance after) {
            if (userId <= 0 || assetId < 0 || before == null && after == null) {
                throw new IllegalArgumentException("invalid balance patch change");
            }
            int index = indexOf(userId, assetId);
            if (index < 0) {
                ensureCapacity();
                userIds[size] = userId;
                assetIds[size] = assetId;
                beforeValues[size] = before;
                afterValues[size] = after;
                size++;
                if (!Objects.equals(before, after)) changedCount++;
                return;
            }
            UserBalance existingBefore = beforeValues[index];
            UserBalance existingAfter = afterValues[index];
            if (!Objects.equals(before, existingBefore) && !Objects.equals(before, existingAfter)) {
                throw new IllegalArgumentException("conflicting before-value for balance patch key");
            }
            boolean changedBefore = !Objects.equals(existingBefore, existingAfter);
            afterValues[index] = after;
            boolean changedAfter = !Objects.equals(existingBefore, after);
            if (changedBefore != changedAfter) changedCount += changedAfter ? 1 : -1;
        }

        private List<BalanceChange> seal() {
            if (changedCount == 0) return List.of();
            ArrayList<BalanceChange> result = new ArrayList<>(changedCount);
            for (int index = 0; index < size; index++) {
                if (!Objects.equals(beforeValues[index], afterValues[index])) {
                    result.add(new BalanceChange(new BalanceKey(userIds[index], assetIds[index]),
                            beforeValues[index], afterValues[index]));
                }
            }
            return java.util.Collections.unmodifiableList(result);
        }

        private void forEachChanged(BalanceChangeConsumer consumer) {
            for (int index = 0; index < size; index++) {
                UserBalance before = beforeValues[index];
                UserBalance after = afterValues[index];
                if (!Objects.equals(before, after)) {
                    consumer.accept(userIds[index], assetIds[index], before, after);
                }
            }
        }

        private int indexOf(long userId, int assetId) {
            for (int index = 0; index < size; index++) {
                if (userIds[index] == userId && assetIds[index] == assetId) return index;
            }
            return -1;
        }

        private void ensureCapacity() {
            if (size < userIds.length) return;
            int capacity = Math.multiplyExact(userIds.length, 2);
            userIds = java.util.Arrays.copyOf(userIds, capacity);
            assetIds = java.util.Arrays.copyOf(assetIds, capacity);
            beforeValues = java.util.Arrays.copyOf(beforeValues, capacity);
            afterValues = java.util.Arrays.copyOf(afterValues, capacity);
        }
    }

    /** Primitive-key, first-touch ordered changes for global int-keyed entities. */
    private static final class IntChanges<V> {
        private int[] keys = new int[8];
        private Object[] beforeValues = new Object[8];
        private Object[] afterValues = new Object[8];
        private int size;
        private int changedCount;

        private boolean hasChanges() { return changedCount != 0; }
        private int changedCount() { return changedCount; }

        private void reset() {
            for (int index = 0; index < size; index++) {
                beforeValues[index] = null;
                afterValues[index] = null;
            }
            size = 0;
            changedCount = 0;
        }

        private void record(int key, V before, V after) {
            if (key < 0 || before == null && after == null) {
                throw new IllegalArgumentException("invalid int-keyed patch change");
            }
            int index = indexOf(key);
            if (index < 0) {
                ensureCapacity();
                keys[size] = key;
                beforeValues[size] = before;
                afterValues[size] = after;
                size++;
                if (!Objects.equals(before, after)) changedCount++;
                return;
            }
            V existingBefore = before(index);
            V existingAfter = after(index);
            if (!Objects.equals(before, existingBefore) && !Objects.equals(before, existingAfter)) {
                throw new IllegalArgumentException("conflicting before-value for int patch key " + key);
            }
            boolean changedBefore = !Objects.equals(existingBefore, existingAfter);
            afterValues[index] = after;
            boolean changedAfter = !Objects.equals(existingBefore, after);
            if (changedBefore != changedAfter) changedCount += changedAfter ? 1 : -1;
        }

        private <R> List<R> seal(IntChangeFunction<V, R> materializer) {
            if (changedCount == 0) return List.of();
            ArrayList<R> result = new ArrayList<>(changedCount);
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) result.add(materializer.apply(keys[index], before, after));
            }
            return java.util.Collections.unmodifiableList(result);
        }

        private void forEachChanged(IntChangeConsumer<V> consumer) {
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) consumer.accept(keys[index], before, after);
            }
        }

        private int indexOf(int key) {
            for (int index = 0; index < size; index++) if (keys[index] == key) return index;
            return -1;
        }

        private void ensureCapacity() {
            if (size < keys.length) return;
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            beforeValues = java.util.Arrays.copyOf(beforeValues, capacity);
            afterValues = java.util.Arrays.copyOf(afterValues, capacity);
        }

        @SuppressWarnings("unchecked")
        private V before(int index) { return (V) beforeValues[index]; }

        @SuppressWarnings("unchecked")
        private V after(int index) { return (V) afterValues[index]; }
    }

    /** Primitive-key, first-touch ordered changes for lane-owned entities. */
    private static final class LongChanges<V> {
        private long[] keys = new long[8];
        private Object[] beforeValues = new Object[8];
        private Object[] afterValues = new Object[8];
        private int size;
        private int changedCount;

        private void captureBefore(long key, V before) {
            if (contains(key)) return;
            append(key, before, before);
        }

        private V before(long key) {
            int index = indexOf(key);
            return index < 0 ? null : before(index);
        }

        private boolean contains(long key) { return indexOf(key) >= 0; }
        private boolean hasChanges() { return changedCount != 0; }
        private int changedCount() { return changedCount; }

        private void reset() {
            for (int index = 0; index < size; index++) {
                beforeValues[index] = null;
                afterValues[index] = null;
            }
            size = 0;
            changedCount = 0;
        }

        private void record(long key, V before, V after) {
            if (before == null && after == null) {
                throw new IllegalArgumentException("invalid primitive patch change");
            }
            int index = indexOf(key);
            if (index < 0) {
                append(key, before, after);
                if (!Objects.equals(before, after)) changedCount++;
                return;
            }
            V existingBefore = before(index);
            V existingAfter = after(index);
            if (!Objects.equals(before, existingBefore) && !Objects.equals(before, existingAfter)) {
                throw new IllegalArgumentException("conflicting before-value for patch key " + key);
            }
            boolean changedBefore = !Objects.equals(existingBefore, existingAfter);
            afterValues[index] = after;
            boolean changedAfter = !Objects.equals(existingBefore, after);
            if (changedBefore != changedAfter) changedCount += changedAfter ? 1 : -1;
        }

        private <R> List<R> seal(LongChangeFunction<V, R> materializer) {
            if (changedCount == 0) return List.of();
            ArrayList<R> result = new ArrayList<>(changedCount);
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) result.add(materializer.apply(keys[index], before, after));
            }
            return java.util.Collections.unmodifiableList(result);
        }

        private void forEachChanged(LongChangeConsumer<V> consumer) {
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) consumer.accept(keys[index], before, after);
            }
        }

        private void append(long key, V before, V after) {
            if (size == keys.length) {
                int capacity = Math.multiplyExact(size, 2);
                keys = java.util.Arrays.copyOf(keys, capacity);
                beforeValues = java.util.Arrays.copyOf(beforeValues, capacity);
                afterValues = java.util.Arrays.copyOf(afterValues, capacity);
            }
            keys[size] = key;
            beforeValues[size] = before;
            afterValues[size] = after;
            size++;
        }

        private int indexOf(long key) {
            for (int index = 0; index < size; index++) if (keys[index] == key) return index;
            return -1;
        }

        @SuppressWarnings("unchecked")
        private V before(int index) { return (V) beforeValues[index]; }

        @SuppressWarnings("unchecked")
        private V after(int index) { return (V) afterValues[index]; }
    }

    private static final class Changes<K, V> {
        private Object[] keys = new Object[8];
        private Object[] beforeValues = new Object[8];
        private Object[] afterValues = new Object[8];
        private int size;
        private int changedCount;

        private void captureBefore(K key, V before) {
            if (key == null) throw new IllegalArgumentException("invalid typed patch key");
            if (indexOf(key) >= 0) return;
            append(key, before, before);
        }

        private V before(K key) {
            int index = indexOf(key);
            return index < 0 ? null : before(index);
        }

        private boolean contains(K key) {
            return indexOf(key) >= 0;
        }

        private boolean hasChanges() {
            return changedCount != 0;
        }

        private int changedCount() { return changedCount; }

        private void reset() {
            for (int index = 0; index < size; index++) {
                keys[index] = null;
                beforeValues[index] = null;
                afterValues[index] = null;
            }
            size = 0;
            changedCount = 0;
        }

        private void record(K key, V before, V after) {
            if (key == null || before == null && after == null) {
                throw new IllegalArgumentException("invalid typed patch change");
            }
            int index = indexOf(key);
            if (index < 0) {
                append(key, before, after);
                if (!Objects.equals(before, after)) changedCount++;
                return;
            }
            V existingBefore = before(index);
            V existingAfter = after(index);
            if (!Objects.equals(before, existingBefore) && !Objects.equals(before, existingAfter)) {
                throw new IllegalArgumentException("conflicting before-value for patch key " + key);
            }
            boolean changedBefore = !Objects.equals(existingBefore, existingAfter);
            afterValues[index] = after;
            boolean changedAfter = !Objects.equals(existingBefore, after);
            if (changedBefore != changedAfter) changedCount += changedAfter ? 1 : -1;
        }

        private <R> List<R> seal(ChangeFunction<K, V, R> materializer) {
            if (changedCount == 0) return List.of();
            ArrayList<R> result = new ArrayList<>(changedCount);
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) {
                    result.add(materializer.apply(key(index), before, after));
                }
            }
            return java.util.Collections.unmodifiableList(result);
        }

        private void forEachChanged(ChangeConsumer3<K, V> consumer) {
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) consumer.accept(key(index), before, after);
            }
        }

        private <R> R single(BiFunction<V, V, R> materializer) {
            if (changedCount == 0) return null;
            for (int index = 0; index < size; index++) {
                V before = before(index);
                V after = after(index);
                if (!Objects.equals(before, after)) return materializer.apply(before, after);
            }
            throw new IllegalStateException("changed patch entry is missing");
        }

        private void append(K key, V before, V after) {
            ensureCapacity();
            keys[size] = key;
            beforeValues[size] = before;
            afterValues[size] = after;
            size++;
        }

        private int indexOf(Object key) {
            if (key == null) return -1;
            for (int index = 0; index < size; index++) {
                if (keys[index].equals(key)) return index;
            }
            return -1;
        }

        private void ensureCapacity() {
            if (size < keys.length) return;
            int capacity = Math.multiplyExact(keys.length, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            beforeValues = java.util.Arrays.copyOf(beforeValues, capacity);
            afterValues = java.util.Arrays.copyOf(afterValues, capacity);
        }

        @SuppressWarnings("unchecked")
        private K key(int index) { return (K) keys[index]; }

        @SuppressWarnings("unchecked")
        private V before(int index) { return (V) beforeValues[index]; }

        @SuppressWarnings("unchecked")
        private V after(int index) { return (V) afterValues[index]; }
    }
    private record ReservationValue(ReservationRuntime value, boolean pending) {
        private ReservationValue {
            if (value == null && pending) throw new IllegalArgumentException("absent reservation cannot be pending");
        }
    }
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
        for (int index = 0; index < ids.size(); index++) {
            Long id = ids.get(index);
            if (id == null || id <= 0) throw new IllegalArgumentException("invalid terminal " + domain + " id");
            for (int previous = 0; previous < index; previous++) {
                if (id.equals(ids.get(previous))) {
                    throw new IllegalArgumentException("duplicate terminal " + domain + " id");
                }
            }
        }
        return List.copyOf(ids);
    }

    private static <T> List<T> canonicalDistinct(List<T> values, String message) {
        for (int index = 0; index < values.size(); index++) {
            T value = Objects.requireNonNull(values.get(index), message);
            for (int previous = 0; previous < index; previous++) {
                if (value.equals(values.get(previous))) throw new IllegalArgumentException(message);
            }
        }
        return List.copyOf(values);
    }
}
