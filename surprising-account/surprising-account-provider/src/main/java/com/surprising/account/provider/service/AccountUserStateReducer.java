package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionChange;
import com.surprising.account.provider.model.PositionState;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 账户用户分区的内存 reducer。
 *
 * <p>当前承接订单资金预占、释放和永续成交结算。它只接受已经初始化的永续账户快照，
 * 快照缺失、序号跳跃、账户版本过期或余额不足都会失败关闭，绝不回退查询数据库。其他命令
 * 在 reducer 完成前返回 UNSUPPORTED，由迁移编排器单独处理，不能伪装成已由内存状态裁决。</p>
 */
@Service
public class AccountUserStateReducer {

    private final ObjectMapper objectMapper;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionCommandLane lane;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final PositionCalculator positionCalculator;
    private final Map<UserPartitionKey, AccountUserReducerState> states = new java.util.concurrent.ConcurrentHashMap<>();

    public AccountUserStateReducer(ObjectMapper objectMapper,
                                   UserPartitionStateStore stateStore,
                                   UserPartitionCommandLane lane) {
        this(objectMapper, stateStore, lane, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountUserStateReducer(ObjectMapper objectMapper,
                                   UserPartitionStateStore stateStore,
                                   UserPartitionCommandLane lane,
                                   InstrumentSnapshotCache instrumentSnapshotCache,
                                   PositionCalculator positionCalculator) {
        this.objectMapper = objectMapper;
        this.stateStore = stateStore;
        this.lane = lane;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.positionCalculator = positionCalculator;
    }

    /** 内部 RPC 启动初始化使用；初始化后热路径只读取本地状态。 */
    public void initialize(PerpetualAccountStateUpdatedEvent snapshot) {
        if (snapshot == null || snapshot.productLine() != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("永续账户快照不完整");
        }
        UserPartitionKey partition = new UserPartitionKey(snapshot.productLine(), snapshot.userId());
        AccountUserReducerState state = new AccountUserReducerState(snapshot, List.of());
        stateStore.initialize(partition, serialize(state));
        states.putIfAbsent(partition, state);
    }

    public Optional<AccountUserReducerState> state(UserPartitionKey partition) {
        AccountUserReducerState cached = states.get(partition);
        if (cached != null) {
            return Optional.of(cached);
        }
        return stateStore.read(partition).map(snapshot -> {
            AccountUserReducerState value = deserialize(snapshot.state());
            states.putIfAbsent(partition, value);
            return value;
        });
    }

    public Reduction apply(AccountUserCommand command, long sequence) {
        if (command == null || sequence <= 0L) {
            throw new IllegalArgumentException("账户 reducer 命令和序号不能为空");
        }
        UserPartitionKey partition = new UserPartitionKey(command.productLine(), command.userId());
        return lane.execute(partition, () -> applyLocked(partition, command, sequence));
    }

    private Reduction applyLocked(UserPartitionKey partition,
                                  AccountUserCommand command,
                                  long sequence) {
        AccountUserReducerState current = state(partition)
                .orElseThrow(() -> new AccountStateUnavailableException(
                        "账户 JVM 快照尚未初始化，拒绝资金命令: " + partition.value()));
        long currentSequence = stateStore.lastAppliedSequence(partition);
        if (sequence <= currentSequence) {
            return new Reduction(ApplyStatus.ALREADY_APPLIED, null, null, current);
        }
        if (sequence != currentSequence + 1L) {
            throw new IllegalStateException("账户 reducer 序号不连续 partition=" + partition.value()
                    + " current=" + currentSequence + " requested=" + sequence);
        }
        Reduction reduction = switch (command.commandType()) {
            case ORDER_RESERVE -> reserve(current, command);
            case ORDER_RELEASE -> release(current, command);
            case TRADE_SIDE_SETTLE -> trade(current, command);
            default -> new Reduction(ApplyStatus.UNSUPPORTED, null, "COMMAND_NOT_REDUCED", current);
        };
        if (reduction.status() != ApplyStatus.UNSUPPORTED) {
            stateStore.apply(partition, sequence, serialize(reduction.nextState()));
            states.put(partition, reduction.nextState());
        }
        return reduction;
    }

    private Reduction reserve(AccountUserReducerState current, AccountUserCommand command) {
        OrderReserveAccountCommand reserve = readPayload(command, OrderReserveAccountCommand.class);
        if (reserve.accountType() != AccountType.USDT_PERPETUAL
                || reserve.reservationKind() == com.surprising.account.api.model.OrderReservationKind.SPOT_ASSET) {
            return rejected(current, "ACCOUNT_SCOPE_UNSUPPORTED", "永续账户 reducer 不接受现货预占");
        }
        if (reserve.expectedAccountRevision() > 0L
                && reserve.expectedAccountRevision() != current.snapshot().accountRevision()) {
            return rejected(current, "ACCOUNT_REVISION_CONFLICT", "账户快照修订号已过期");
        }
        if (current.reservations().stream().anyMatch(value -> value.orderId() == reserve.orderId())) {
            return rejected(current, "DUPLICATE_ORDER_RESERVATION", "订单预占已经存在");
        }
        BalanceMutation mutation = moveBalance(current.snapshot(), reserve.asset(), reserve.reservedUnits(), true);
        if (!mutation.accepted()) {
            return rejected(current, "INSUFFICIENT_AVAILABLE_BALANCE", "可用余额不足");
        }
        List<AccountUserReducerState.Reservation> reservations = new ArrayList<>(current.reservations());
        reservations.add(new AccountUserReducerState.Reservation(reserve.orderId(), reserve.accountType(),
                reserve.asset(), reserve.reservedUnits(), 0L, reserve.orderQuantitySteps()));
        PerpetualAccountStateUpdatedEvent snapshot = nextSnapshot(
                adjustOrderLock(mutation.snapshot(), reserve.asset(), reserve.reservedUnits()),
                current.snapshot().accountRevision());
        return new Reduction(ApplyStatus.APPLIED, jsonResult("orderId", reserve.orderId(),
                "reservedUnits", reserve.reservedUnits()), null,
                new AccountUserReducerState(snapshot, reservations));
    }

    private Reduction release(AccountUserReducerState current, AccountUserCommand command) {
        OrderReleaseAccountCommand release = readPayload(command, OrderReleaseAccountCommand.class);
        AccountUserReducerState.Reservation reservation = current.reservations().stream()
                .filter(value -> value.orderId() == release.orderId())
                .findFirst().orElse(null);
        if (reservation == null) {
            if (release.reservationExpected()) {
                return rejected(current, "RESERVATION_NOT_FOUND", "订单预占快照不存在");
            }
            return new Reduction(ApplyStatus.APPLIED, jsonResult("orderId", release.orderId(),
                    "releasedUnits", 0L), null, current);
        }
        long unavailable = reservation.releasedUnits();
        long releasable = Math.max(0L, Math.subtractExact(reservation.reservedUnits(), unavailable));
        long amount = release.releaseAll()
                ? releasable
                : AccountMarginReleaseMath.releaseForExecuted(reservation.reservedUnits(),
                        reservation.releasedUnits(), 0L, release.quantitySteps(), release.remainingQuantitySteps());
        if (amount <= 0L) {
            return new Reduction(ApplyStatus.APPLIED, jsonResult("orderId", release.orderId(),
                    "releasedUnits", 0L), null, current);
        }
        BalanceMutation mutation = moveBalance(current.snapshot(), reservation.asset(), amount, false);
        if (!mutation.accepted()) {
            throw new IllegalStateException("账户锁定余额不足，拒绝释放预占");
        }
        List<AccountUserReducerState.Reservation> reservations = current.reservations().stream()
                .map(value -> value.orderId() == release.orderId()
                        ? new AccountUserReducerState.Reservation(value.orderId(), value.accountType(), value.asset(),
                        value.reservedUnits(), Math.addExact(value.releasedUnits(), amount), value.orderQuantitySteps())
                        : value)
                .toList();
        PerpetualAccountStateUpdatedEvent snapshot = nextSnapshot(
                adjustOrderLock(mutation.snapshot(), reservation.asset(), -amount),
                current.snapshot().accountRevision());
        return new Reduction(ApplyStatus.APPLIED, jsonResult("orderId", release.orderId(),
                "releasedUnits", amount), null, new AccountUserReducerState(snapshot, reservations));
    }

    private Reduction trade(AccountUserReducerState current, AccountUserCommand command) {
        if (instrumentSnapshotCache == null || positionCalculator == null) {
            return new Reduction(ApplyStatus.UNSUPPORTED, null, "INSTRUMENT_SNAPSHOT_UNAVAILABLE", current);
        }
        TradeSideSettlementCommand sideCommand = readPayload(command, TradeSideSettlementCommand.class);
        MatchTradeEvent trade = sideCommand.trade();
        if (sideCommand.userId() != command.userId()) {
            throw new AccountCommandPoisonPillException("成交结算用户与账户命令不一致");
        }
        if (current.settledTradeIds().contains(trade.tradeId())) {
            return new Reduction(ApplyStatus.APPLIED, jsonResult("tradeId", trade.tradeId(),
                    "duplicate", true), null, current);
        }
        TradeParticipantRole role = sideCommand.participantRole();
        long orderId = sideCommand.orderId();
        OrderSide fillSide = role == TradeParticipantRole.TAKER
                ? trade.takerSide() : opposite(trade.takerSide());
        MarginMode marginMode = role == TradeParticipantRole.TAKER
                ? trade.takerMarginMode() : trade.makerMarginMode();
        PositionSide positionSide = role == TradeParticipantRole.TAKER
                ? trade.takerPositionSide() : trade.makerPositionSide();
        long instrumentVersion = role == TradeParticipantRole.TAKER
                ? trade.takerInstrumentVersion() : trade.makerInstrumentVersion();
        ContractSpec fillSpec = contractSpec(trade.symbol(), instrumentVersion);
        if (fillSpec.contractType().productLine() != ProductLine.LINEAR_PERPETUAL) {
            return new Reduction(ApplyStatus.UNSUPPORTED, null, "PRODUCT_LINE_UNSUPPORTED", current);
        }
        PerpetualAccountStateUpdatedEvent.Position previous = findPosition(
                current.snapshot(), trade.symbol(), marginMode, positionSide);
        PositionState currentPosition = previous == null
                ? new PositionState(0L, instrumentVersion, 0L, 0L, 0L)
                : new PositionState(previous.signedQuantitySteps(), previous.instrumentVersion(),
                previous.entryPriceTicks(), previous.entryValueTicks(), previous.realizedPnlUnits());
        ContractSpec positionSpec = currentPosition.signedQuantitySteps() == 0L
                ? fillSpec : contractSpec(trade.symbol(), currentPosition.instrumentVersion());
        PositionChange change = positionCalculator.apply(currentPosition, fillSide, trade.priceTicks(),
                trade.quantitySteps(), positionSpec, fillSpec);
        long closeSteps = MarginTransferMath.closeSteps(currentPosition.signedQuantitySteps(), fillSide,
                trade.quantitySteps());
        long openSteps = Math.subtractExact(trade.quantitySteps(), closeSteps);
        AccountUserReducerState next = current;
        if (closeSteps > 0L && !fillSpec.contractType().isOption()) {
            next = applyBalanceDelta(next, fillSpec.settleAsset(), change.realizedPnlDeltaUnits());
            next = releasePositionMargin(next, trade.symbol(), marginMode, positionSide, closeSteps,
                    Math.absExact(currentPosition.signedQuantitySteps()));
        }
        long actualMarginUnits = openSteps == 0L ? 0L
                : MarginTransferMath.openingInitialMarginUnits(fillSpec, trade.priceTicks(), openSteps);
        if (openSteps > 0L) {
            next = consumeOrderMargin(next, sideCommand, orderId, trade.quantitySteps(), openSteps,
                    actualMarginUnits);
            if (actualMarginUnits > 0L) {
                next = addPositionMargin(next, trade.symbol(), fillSpec.settleAsset(), marginMode,
                        positionSide, actualMarginUnits);
            }
        }
        long feeRatePpm = role == TradeParticipantRole.TAKER
                ? trade.takerFeeRatePpm() : trade.makerFeeRatePpm();
        next = applyBalanceDelta(next, fillSpec.settleAsset(),
                TradeFeeMath.feeDeltaUnits(fillSpec, trade.priceTicks(), trade.quantitySteps(), feeRatePpm));
        next = replacePosition(next, trade.symbol(), marginMode, positionSide, change.next(), trade.eventTime());
        List<Long> settledTradeIds = new ArrayList<>(next.settledTradeIds());
        settledTradeIds.add(trade.tradeId());
        PerpetualAccountStateUpdatedEvent snapshot = nextSnapshot(next.snapshot(),
                current.snapshot().accountRevision());
        return new Reduction(ApplyStatus.APPLIED, jsonResult("tradeId", trade.tradeId(),
                "orderId", orderId), null,
                new AccountUserReducerState(snapshot, next.reservations(), settledTradeIds));
    }

    private ContractSpec contractSpec(String symbol, long version) {
        InstrumentResponse instrument = instrumentSnapshotCache.version(ProductLine.LINEAR_PERPETUAL, symbol, version)
                .orElseThrow(() -> new AccountStateUnavailableException(
                        "成交合约 JVM 快照不存在: " + symbol + ":" + version));
        long scale = instrumentSnapshotCache.scale(ProductLine.LINEAR_PERPETUAL, instrument.settleAsset())
                .orElseThrow(() -> new AccountStateUnavailableException(
                        "成交资产精度 JVM 快照不存在: " + instrument.settleAsset()));
        return new ContractSpec(instrument.version(), instrument.contractType(), instrument.settleAsset(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), scale,
                instrument.initialMarginRatePpm(), instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm());
    }

    private PerpetualAccountStateUpdatedEvent.Position findPosition(
            PerpetualAccountStateUpdatedEvent snapshot,
            String symbol,
            MarginMode marginMode,
            PositionSide positionSide) {
        return snapshot.positions().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.marginMode() == marginMode)
                .filter(value -> value.positionSide() == positionSide)
                .findFirst().orElse(null);
    }

    private AccountUserReducerState consumeOrderMargin(AccountUserReducerState current,
                                                       TradeSideSettlementCommand sideCommand,
                                                       long orderId,
                                                       long fillQuantitySteps,
                                                       long openSteps,
                                                       long actualMarginUnits) {
        if (sideCommand.reservedUnits() <= 0L && openSteps > 0L) {
            throw new AccountCommandRejectedException("ORDER_MARGIN_RESERVATION_MISSING",
                    "开仓成交缺少订单保证金预占");
        }
        AccountUserReducerState.Reservation reservation = current.reservations().stream()
                .filter(value -> value.orderId() == orderId).findFirst().orElse(null);
        if (reservation == null && openSteps > 0L) {
            throw new AccountCommandRejectedException("ORDER_MARGIN_RESERVATION_MISSING",
                    "账户 reducer 中不存在订单保证金预占");
        }
        if (openSteps <= 0L || reservation == null) {
            return current;
        }
        long allocated = Math.multiplyExact(reservation.reservedUnits(), fillQuantitySteps)
                / reservation.orderQuantitySteps();
        if (actualMarginUnits > allocated) {
            throw new AccountCommandRejectedException("ORDER_MARGIN_EXCEEDS_RESERVATION",
                    "成交所需保证金超过订单预占");
        }
        long released = Math.subtractExact(allocated, actualMarginUnits);
        AccountUserReducerState updated = current;
        if (released > 0L) {
            updated = applyBalanceTransfer(updated, reservation.asset(), released, true);
        }
        long nextReleased = Math.addExact(reservation.releasedUnits(), released);
        long nextConsumed = Math.addExact(reservation.consumedUnits(), actualMarginUnits);
        List<AccountUserReducerState.Reservation> reservations = updated.reservations().stream()
                .map(value -> value.orderId() == orderId
                        ? new AccountUserReducerState.Reservation(value.orderId(), value.accountType(), value.asset(),
                        value.reservedUnits(), nextReleased, nextConsumed, value.orderQuantitySteps())
                        : value)
                .toList();
        return new AccountUserReducerState(updated.snapshot(), reservations, updated.settledTradeIds());
    }

    private AccountUserReducerState applyBalanceDelta(AccountUserReducerState current,
                                                       String asset,
                                                       long delta) {
        if (delta == 0L) {
            return current;
        }
        List<PerpetualAccountStateUpdatedEvent.Balance> balances = new ArrayList<>();
        boolean found = false;
        for (PerpetualAccountStateUpdatedEvent.Balance balance : current.snapshot().balances()) {
            if (!balance.asset().equalsIgnoreCase(asset)) {
                balances.add(balance);
                continue;
            }
            found = true;
            long available = delta > 0L
                    ? Math.addExact(balance.availableUnits(), delta)
                    : Math.subtractExact(balance.availableUnits(), Math.absExact(delta));
            if (available < 0L) {
                throw new AccountCommandRejectedException("ACCOUNT_BALANCE_INSUFFICIENT",
                        "账户可用余额不足，拒绝成交结算");
            }
            balances.add(new PerpetualAccountStateUpdatedEvent.Balance(
                    balance.asset(), available, balance.lockedUnits()));
        }
        if (!found) {
            throw new AccountCommandRejectedException("ACCOUNT_ASSET_NOT_FOUND", "结算资产不存在");
        }
        PerpetualAccountStateUpdatedEvent previous = current.snapshot();
        PerpetualAccountStateUpdatedEvent snapshot = new PerpetualAccountStateUpdatedEvent(
                previous.schemaVersion(), previous.eventId(), previous.accountRevision(), previous.productLine(),
                previous.userId(), previous.accountType(), balances, previous.deficits(), previous.positions(),
                previous.positionMargins(), previous.orderLocks(), previous.positionMode(), previous.eventTime(),
                previous.traceId());
        return new AccountUserReducerState(snapshot, current.reservations(), current.settledTradeIds());
    }

    private AccountUserReducerState applyBalanceTransfer(AccountUserReducerState current,
                                                          String asset,
                                                          long amount,
                                                          boolean lockedToAvailable) {
        BalanceMutation mutation = moveBalance(current.snapshot(), asset, amount, !lockedToAvailable);
        if (!mutation.accepted()) {
            throw new AccountCommandRejectedException("ACCOUNT_BALANCE_INSUFFICIENT",
                    "账户余额不足，拒绝成交结算");
        }
        return new AccountUserReducerState(mutation.snapshot(), current.reservations(), current.settledTradeIds());
    }

    private AccountUserReducerState addPositionMargin(AccountUserReducerState current,
                                                       String symbol,
                                                       String asset,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide,
                                                       long amount) {
        List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins = new ArrayList<>();
        boolean found = false;
        for (PerpetualAccountStateUpdatedEvent.PositionMargin margin : current.snapshot().positionMargins()) {
            if (margin.symbol().equalsIgnoreCase(symbol) && margin.asset().equalsIgnoreCase(asset)
                    && margin.marginMode() == marginMode && margin.positionSide() == positionSide) {
                margins.add(new PerpetualAccountStateUpdatedEvent.PositionMargin(margin.symbol(), margin.asset(),
                        margin.marginMode(), margin.positionSide(), Math.addExact(margin.marginUnits(), amount)));
                found = true;
            } else {
                margins.add(margin);
            }
        }
        if (!found) {
            margins.add(new PerpetualAccountStateUpdatedEvent.PositionMargin(symbol, asset, marginMode,
                    positionSide, amount));
        }
        return withPositionMargins(current, margins);
    }

    private AccountUserReducerState releasePositionMargin(AccountUserReducerState current,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide,
                                                           long closeSteps,
                                                           long positionSteps) {
        List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins = new ArrayList<>();
        AccountUserReducerState updated = current;
        for (PerpetualAccountStateUpdatedEvent.PositionMargin margin : current.snapshot().positionMargins()) {
            if (!margin.symbol().equalsIgnoreCase(symbol) || margin.marginMode() != marginMode
                    || margin.positionSide() != positionSide) {
                margins.add(margin);
                continue;
            }
            long amount = MarginTransferMath.positionMarginReleaseAmount(margin.marginUnits(), closeSteps,
                    positionSteps);
            if (amount <= 0L) {
                margins.add(margin);
                continue;
            }
            updated = applyBalanceTransfer(updated, margin.asset(), amount, true);
            long remaining = Math.subtractExact(margin.marginUnits(), amount);
            if (remaining > 0L) {
                margins.add(new PerpetualAccountStateUpdatedEvent.PositionMargin(margin.symbol(), margin.asset(),
                        margin.marginMode(), margin.positionSide(), remaining));
            }
        }
        return withPositionMargins(updated, margins);
    }

    private AccountUserReducerState withPositionMargins(AccountUserReducerState current,
                                                         List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins) {
        PerpetualAccountStateUpdatedEvent previous = current.snapshot();
        PerpetualAccountStateUpdatedEvent snapshot = new PerpetualAccountStateUpdatedEvent(
                previous.schemaVersion(), previous.eventId(), previous.accountRevision(), previous.productLine(),
                previous.userId(), previous.accountType(), previous.balances(), previous.deficits(),
                previous.positions(), margins, previous.orderLocks(), previous.positionMode(), previous.eventTime(),
                previous.traceId());
        return new AccountUserReducerState(snapshot, current.reservations(), current.settledTradeIds());
    }

    private AccountUserReducerState replacePosition(AccountUserReducerState current,
                                                     String symbol,
                                                     MarginMode marginMode,
                                                     PositionSide positionSide,
                                                     PositionState position,
                                                     Instant eventTime) {
        List<PerpetualAccountStateUpdatedEvent.Position> positions = new ArrayList<>();
        boolean found = false;
        for (PerpetualAccountStateUpdatedEvent.Position previous : current.snapshot().positions()) {
            if (previous.symbol().equalsIgnoreCase(symbol) && previous.marginMode() == marginMode
                    && previous.positionSide() == positionSide) {
                positions.add(new PerpetualAccountStateUpdatedEvent.Position(symbol, position.instrumentVersion(),
                        marginMode, positionSide, position.signedQuantitySteps(), position.entryPriceTicks(),
                        position.entryValueTicks(), position.realizedPnlUnits(),
                        eventTime == null ? Instant.now() : eventTime));
                found = true;
            } else {
                positions.add(previous);
            }
        }
        if (!found) {
            positions.add(new PerpetualAccountStateUpdatedEvent.Position(symbol, position.instrumentVersion(),
                    marginMode, positionSide, position.signedQuantitySteps(), position.entryPriceTicks(),
                    position.entryValueTicks(), position.realizedPnlUnits(),
                    eventTime == null ? Instant.now() : eventTime));
        }
        PerpetualAccountStateUpdatedEvent previous = current.snapshot();
        PerpetualAccountStateUpdatedEvent snapshot = new PerpetualAccountStateUpdatedEvent(
                previous.schemaVersion(), previous.eventId(), previous.accountRevision(), previous.productLine(),
                previous.userId(), previous.accountType(), previous.balances(), previous.deficits(), positions,
                previous.positionMargins(), previous.orderLocks(), previous.positionMode(), previous.eventTime(),
                previous.traceId());
        return new AccountUserReducerState(snapshot, current.reservations(), current.settledTradeIds());
    }

    private OrderSide opposite(OrderSide side) {
        return side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    }

    private BalanceMutation moveBalance(PerpetualAccountStateUpdatedEvent snapshot,
                                        String asset,
                                        long amount,
                                        boolean availableToLocked) {
        if (amount <= 0L) {
            return new BalanceMutation(false, snapshot);
        }
        List<PerpetualAccountStateUpdatedEvent.Balance> balances = new ArrayList<>();
        boolean found = false;
        boolean accepted = true;
        for (PerpetualAccountStateUpdatedEvent.Balance balance : snapshot.balances()) {
            if (!balance.asset().equalsIgnoreCase(asset)) {
                balances.add(balance);
                continue;
            }
            found = true;
            if (availableToLocked && balance.availableUnits() < amount) {
                accepted = false;
                balances.add(balance);
            } else if (!availableToLocked && balance.lockedUnits() < amount) {
                accepted = false;
                balances.add(balance);
            } else {
                balances.add(availableToLocked
                        ? new PerpetualAccountStateUpdatedEvent.Balance(balance.asset(),
                        Math.subtractExact(balance.availableUnits(), amount),
                        Math.addExact(balance.lockedUnits(), amount))
                        : new PerpetualAccountStateUpdatedEvent.Balance(balance.asset(),
                        Math.addExact(balance.availableUnits(), amount),
                        Math.subtractExact(balance.lockedUnits(), amount)));
            }
        }
        if (!found || !accepted) {
            return new BalanceMutation(false, snapshot);
        }
        return new BalanceMutation(true, new PerpetualAccountStateUpdatedEvent(
                snapshot.schemaVersion(), snapshot.eventId(), snapshot.accountRevision(), snapshot.productLine(),
                snapshot.userId(), snapshot.accountType(), balances, snapshot.deficits(), snapshot.positions(),
                snapshot.positionMargins(), snapshot.orderLocks(), snapshot.positionMode(), snapshot.eventTime(),
                snapshot.traceId()));
    }

    private PerpetualAccountStateUpdatedEvent nextSnapshot(PerpetualAccountStateUpdatedEvent previous,
                                                           long previousRevision) {
        Map<String, Long> locks = new LinkedHashMap<>();
        previous.orderLocks().forEach(value -> locks.put(value.asset(), value.lockedUnits()));
        return new PerpetualAccountStateUpdatedEvent(
                previous.schemaVersion(), Math.addExact(previous.eventId(), 1L),
                Math.addExact(previousRevision, 1L), previous.productLine(), previous.userId(),
                previous.accountType(), previous.balances(), previous.deficits(), previous.positions(),
                previous.positionMargins(), locks.entrySet().stream()
                        .filter(value -> value.getValue() > 0L)
                        .map(value -> new PerpetualAccountStateUpdatedEvent.OrderLock(value.getKey(), value.getValue()))
                .toList(), previous.positionMode(), Instant.now(), previous.traceId());
    }

    private PerpetualAccountStateUpdatedEvent adjustOrderLock(PerpetualAccountStateUpdatedEvent snapshot,
                                                              String asset,
                                                              long delta) {
        Map<String, Long> locks = new LinkedHashMap<>();
        snapshot.orderLocks().forEach(value -> locks.put(value.asset(), value.lockedUnits()));
        long current = locks.getOrDefault(asset.toUpperCase(java.util.Locale.ROOT), 0L);
        long next = Math.addExact(current, delta);
        if (next < 0L) {
            throw new IllegalStateException("账户订单锁定汇总不能为负数");
        }
        if (next == 0L) {
            locks.remove(asset.toUpperCase(java.util.Locale.ROOT));
        } else {
            locks.put(asset.toUpperCase(java.util.Locale.ROOT), next);
        }
        return new PerpetualAccountStateUpdatedEvent(
                snapshot.schemaVersion(), snapshot.eventId(), snapshot.accountRevision(), snapshot.productLine(),
                snapshot.userId(), snapshot.accountType(), snapshot.balances(), snapshot.deficits(),
                snapshot.positions(), snapshot.positionMargins(), locks.entrySet().stream()
                        .map(value -> new PerpetualAccountStateUpdatedEvent.OrderLock(value.getKey(), value.getValue()))
                        .toList(), snapshot.positionMode(), snapshot.eventTime(), snapshot.traceId());
    }

    private Reduction rejected(AccountUserReducerState state, String code, String message) {
        return new Reduction(ApplyStatus.REJECTED,
                jsonResult("errorCode", code, "errorMessage", message), code, state);
    }

    private String jsonResult(String key, Object value, String secondKey, Object secondValue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        result.put(secondKey, secondValue);
        return objectMapper.writeValueAsString(result);
    }

    private <T> T readPayload(AccountUserCommand command, Class<T> type) {
        try {
            return objectMapper.readValue(command.payload(), type);
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException("账户 reducer 无法解析命令负载", ex);
        }
    }

    private byte[] serialize(AccountUserReducerState state) {
        try {
            return objectMapper.writeValueAsString(state).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("账户 reducer 状态序列化失败", ex);
        }
    }

    private AccountUserReducerState deserialize(byte[] state) {
        try {
            return objectMapper.readValue(new String(state, java.nio.charset.StandardCharsets.UTF_8),
                    AccountUserReducerState.class);
        } catch (Exception ex) {
            throw new IllegalStateException("账户 reducer 状态损坏", ex);
        }
    }

    private record BalanceMutation(boolean accepted, PerpetualAccountStateUpdatedEvent snapshot) {
    }

    public enum ApplyStatus {
        APPLIED,
        REJECTED,
        UNSUPPORTED,
        ALREADY_APPLIED
    }

    public record Reduction(ApplyStatus status,
                            String resultPayload,
                            String errorCode,
                            AccountUserReducerState nextState) {
    }
}
