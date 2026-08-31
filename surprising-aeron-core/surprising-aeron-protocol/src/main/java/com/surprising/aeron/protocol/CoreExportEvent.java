package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public record CoreExportEvent(
        long exportSequence,
        long appliedCommandCount,
        long businessStateHash,
        UUID commandId,
        CoreMessageType commandType,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        long userId,
        byte[] commandPayload,
        List<CoreUserStateView> changedUsers,
        List<CoreOrderStateView> changedOrders,
        List<CoreExecutionView> executions,
        List<CoreFundingPaymentView> fundingPayments,
        List<CoreLiquidationView> changedLiquidations,
        List<CoreTreasuryAssetView> changedTreasuryAssets,
        List<CoreTriggerOrderStateView> changedTriggerOrders,
        long beforeBusinessStateHash,
        long beforeFundsStateHash,
        long fundsStateHash,
        int routeVersion,
        long topologyHash,
        long laneRevisionHash,
        long committedCoreSequence,
        CoreMatcherTransition matcherTransition,
        long clusterPosition,
        List<CoreFundsPostingView> fundsPostings,
        CommandFingerprint commandFingerprint,
        List<MatcherEvidence> matcherEvidence,
        TerminalIds terminalIds,
        long previousCoreSequence,
        long coreSequence,
        long previousProjectionSequence,
        long projectionSequence,
        CoreFundingProgressView fundingProgress,
        CoreSettlementProgressView settlementProgress,
        Tombstones tombstones) {

    public CoreExportEvent {
        if (exportSequence <= 0 || appliedCommandCount <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandStatus == null || resultCode == null
                || commandPayload == null || changedUsers == null || changedOrders == null || executions == null
                || fundingPayments == null || changedLiquidations == null || changedTreasuryAssets == null
                || changedTriggerOrders == null || routeVersion != CoreRoute.DEFAULT.version()
                || topologyHash == 0 || laneRevisionHash == 0 || committedCoreSequence != appliedCommandCount
                || matcherTransition == null || matcherTransition.routeVersion() != routeVersion || clusterPosition < 0
                || fundsPostings == null || commandFingerprint == null || matcherEvidence == null
                || terminalIds == null || previousCoreSequence < 0 || coreSequence < previousCoreSequence
                || previousProjectionSequence < 0 || projectionSequence < previousProjectionSequence
                || tombstones == null) {
            throw new IllegalArgumentException("invalid core export event");
        }
        commandPayload = commandPayload.clone();
        changedUsers = List.copyOf(changedUsers);
        changedOrders = List.copyOf(changedOrders);
        executions = List.copyOf(executions);
        fundingPayments = List.copyOf(fundingPayments);
        changedLiquidations = List.copyOf(changedLiquidations);
        changedTreasuryAssets = List.copyOf(changedTreasuryAssets);
        changedTriggerOrders = List.copyOf(changedTriggerOrders);
        fundsPostings = List.copyOf(fundsPostings);
        matcherEvidence = List.copyOf(matcherEvidence);
        requireNonZeroFingerprint(commandFingerprint);
        requireUnique(changedUsers, CoreUserStateView::userId, "changed user");
        requireUnique(changedOrders, CoreOrderStateView::orderId, "changed order");
        requireUnique(changedLiquidations, CoreLiquidationView::liquidationId, "changed liquidation");
        requireUnique(changedTriggerOrders, CoreTriggerOrderStateView::triggerOrderId, "changed trigger order");
        requireUniqueText(changedTreasuryAssets, CoreTreasuryAssetView::asset, "changed treasury asset");
        requireSingleProductLine(changedUsers, changedOrders, changedTriggerOrders);
        requireResolvedTombstones(changedUsers, changedOrders, changedLiquidations, changedTreasuryAssets,
                changedTriggerOrders, tombstones);
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
            orderIds = List.copyOf(orderIds);
            liquidationIds = List.copyOf(liquidationIds);
            triggerOrderIds = List.copyOf(triggerOrderIds);
            requireCanonical(orderIds, Long::compare, value -> value != null && value > 0, "terminal order ID");
            requireCanonical(liquidationIds, Long::compare, value -> value != null && value > 0,
                    "terminal liquidation ID");
            requireCanonical(triggerOrderIds, Long::compare, value -> value != null && value > 0,
                    "terminal trigger order ID");
        }

        public static TerminalIds empty() { return new TerminalIds(List.of(), List.of(), List.of()); }
    }

    public record UserAssetKey(long userId, String asset) {
        public UserAssetKey {
            if (userId <= 0 || asset == null || asset.isBlank() || !asset.equals(asset.trim())) {
                throw new IllegalArgumentException("invalid asset key");
            }
        }
    }

    public record UserOrderKey(long userId, long orderId) {
        public UserOrderKey {
            if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("invalid user order key");
        }
    }

    public record UserPositionKey(long userId, String symbol, CorePositionSide positionSide) {
        public UserPositionKey {
            if (userId <= 0 || symbol == null || symbol.isBlank() || !symbol.equals(symbol.trim())
                    || positionSide == null) {
                throw new IllegalArgumentException("invalid position key");
            }
        }
    }

    public record UserLeverageKey(long userId, String symbol, CoreMarginMode marginMode) {
        public UserLeverageKey {
            if (userId <= 0 || symbol == null || symbol.isBlank() || !symbol.equals(symbol.trim())
                    || marginMode == null) {
                throw new IllegalArgumentException("invalid leverage key");
            }
        }
    }

    public record Tombstones(List<Long> userIds, List<UserAssetKey> balances,
                             List<UserOrderKey> reservations, List<Long> orderIds,
                             List<UserPositionKey> positions, List<UserLeverageKey> leverages,
                             List<Long> liquidationIds, List<Long> algoOrderIds,
                             List<Long> triggerOrderIds, List<String> treasuryAssets) {
        public Tombstones {
            userIds = List.copyOf(userIds);
            balances = List.copyOf(balances);
            reservations = List.copyOf(reservations);
            orderIds = List.copyOf(orderIds);
            positions = List.copyOf(positions);
            leverages = List.copyOf(leverages);
            liquidationIds = List.copyOf(liquidationIds);
            algoOrderIds = List.copyOf(algoOrderIds);
            triggerOrderIds = List.copyOf(triggerOrderIds);
            treasuryAssets = List.copyOf(treasuryAssets);
            requireCanonical(userIds, Long::compare, value -> value != null && value > 0, "deleted user ID");
            requireCanonical(balances, Comparator.comparingLong(UserAssetKey::userId)
                    .thenComparing(UserAssetKey::asset), value -> value != null, "deleted balance key");
            requireCanonical(reservations, Comparator.comparingLong(UserOrderKey::userId)
                    .thenComparingLong(UserOrderKey::orderId), value -> value != null,
                    "deleted reservation key");
            requireCanonical(orderIds, Long::compare, value -> value != null && value > 0, "deleted order ID");
            requireCanonical(positions, Comparator.comparingLong(UserPositionKey::userId)
                    .thenComparing(UserPositionKey::symbol).thenComparing(UserPositionKey::positionSide),
                    value -> value != null, "deleted position key");
            requireCanonical(leverages, Comparator.comparingLong(UserLeverageKey::userId)
                    .thenComparing(UserLeverageKey::symbol).thenComparing(UserLeverageKey::marginMode),
                    value -> value != null, "deleted leverage key");
            requireCanonical(liquidationIds, Long::compare, value -> value != null && value > 0,
                    "deleted liquidation ID");
            requireCanonical(algoOrderIds, Long::compare, value -> value != null && value > 0,
                    "deleted algo order ID");
            requireCanonical(triggerOrderIds, Long::compare, value -> value != null && value > 0,
                    "deleted trigger order ID");
            requireCanonical(treasuryAssets, String::compareTo,
                    value -> value != null && !value.isBlank() && value.equals(value.trim()),
                    "deleted treasury asset");
        }

        public static Tombstones empty() {
            return new Tombstones(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }

        public int itemCount() {
            return userIds.size() + balances.size() + reservations.size() + orderIds.size() + positions.size()
                    + leverages.size() + liquidationIds.size() + algoOrderIds.size() + triggerOrderIds.size()
                    + treasuryAssets.size();
        }
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }

    public byte[] commandPayloadUnsafe() {
        return commandPayload;
    }

    private static void requireNonZeroFingerprint(CommandFingerprint fingerprint) {
        for (byte value : fingerprint.bytes()) {
            if (value != 0) return;
        }
        throw new IllegalArgumentException("command fingerprint must not be zero");
    }

    private static <T> void requireUnique(List<T> values, java.util.function.ToLongFunction<T> key,
                                          String description) {
        java.util.HashSet<Long> keys = new java.util.HashSet<>();
        for (T value : values) {
            if (value == null || !keys.add(key.applyAsLong(value))) {
                throw new IllegalArgumentException("duplicate or null " + description);
            }
        }
    }

    private static <T> void requireUniqueText(List<T> values, java.util.function.Function<T, String> key,
                                              String description) {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (T value : values) {
            if (value == null || !keys.add(key.apply(value))) {
                throw new IllegalArgumentException("duplicate or null " + description);
            }
        }
    }

    private static void requireSingleProductLine(List<CoreUserStateView> users, List<CoreOrderStateView> orders,
                                                 List<CoreTriggerOrderStateView> triggers) {
        ProductLine productLine = null;
        for (CoreUserStateView user : users) {
            if (productLine == null) productLine = user.productLine();
            else if (productLine != user.productLine()) throw new IllegalArgumentException("mixed product lines");
        }
        for (CoreOrderStateView order : orders) {
            if (productLine == null) productLine = order.productLine();
            else if (productLine != order.productLine()) throw new IllegalArgumentException("mixed product lines");
        }
        for (CoreTriggerOrderStateView trigger : triggers) {
            if (productLine == null) productLine = trigger.productLine();
            else if (productLine != trigger.productLine()) {
                throw new IllegalArgumentException("mixed product lines");
            }
        }
    }

    private static void requireResolvedTombstones(
            List<CoreUserStateView> users,
            List<CoreOrderStateView> orders,
            List<CoreLiquidationView> liquidations,
            List<CoreTreasuryAssetView> treasury,
            List<CoreTriggerOrderStateView> triggers,
            Tombstones tombstones) {
        java.util.HashSet<Long> deletedUsers = new java.util.HashSet<>(tombstones.userIds());
        java.util.HashSet<Long> deletedOrders = new java.util.HashSet<>(tombstones.orderIds());
        java.util.HashSet<Long> deletedLiquidations = new java.util.HashSet<>(tombstones.liquidationIds());
        java.util.HashSet<Long> deletedTriggers = new java.util.HashSet<>(tombstones.triggerOrderIds());
        java.util.HashSet<String> deletedTreasury = new java.util.HashSet<>(tombstones.treasuryAssets());
        if (users.stream().anyMatch(user -> deletedUsers.contains(user.userId()))
                || orders.stream().anyMatch(order -> deletedOrders.contains(order.orderId()))
                || liquidations.stream().anyMatch(value -> deletedLiquidations.contains(value.liquidationId()))
                || triggers.stream().anyMatch(value -> deletedTriggers.contains(value.triggerOrderId()))
                || treasury.stream().anyMatch(value -> deletedTreasury.contains(value.asset()))) {
            throw new IllegalArgumentException("Core Fact delete/recreate keys are unresolved");
        }
        java.util.HashSet<UserAssetKey> deletedBalances = new java.util.HashSet<>(tombstones.balances());
        java.util.HashSet<UserOrderKey> deletedReservations = new java.util.HashSet<>(tombstones.reservations());
        java.util.HashSet<UserPositionKey> deletedPositions = new java.util.HashSet<>(tombstones.positions());
        java.util.HashSet<UserLeverageKey> deletedLeverages = new java.util.HashSet<>(tombstones.leverages());
        for (CoreUserStateView user : users) {
            if (user.balances().stream().anyMatch(value -> deletedBalances.contains(
                    new UserAssetKey(user.userId(), value.asset())))
                    || user.reservations().stream().anyMatch(value -> deletedReservations.contains(
                    new UserOrderKey(user.userId(), value.orderId())))
                    || user.positions().stream().anyMatch(value -> deletedPositions.contains(
                    new UserPositionKey(user.userId(), value.symbol(), value.positionSide())))
                    || user.leverages().stream().anyMatch(value -> deletedLeverages.contains(
                    new UserLeverageKey(user.userId(), value.symbol(), value.marginMode())))) {
                throw new IllegalArgumentException("Core Fact nested delete/recreate keys are unresolved");
            }
        }
    }

    private static <T> void requireCanonical(List<T> values, Comparator<? super T> comparator,
                                             Predicate<T> valid, String description) {
        T previous = null;
        for (T value : values) {
            if (!valid.test(value) || previous != null && comparator.compare(previous, value) >= 0) {
                throw new IllegalArgumentException(description + " list is invalid or non-canonical");
            }
            previous = value;
        }
    }
}
