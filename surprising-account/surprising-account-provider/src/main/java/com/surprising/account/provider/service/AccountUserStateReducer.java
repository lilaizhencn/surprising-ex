package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.product.api.ProductLine;
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
 * <p>当前先承接订单资金预占和释放这两个最敏感的操作。它只接受已经初始化的永续账户快照，
 * 快照缺失、序号跳跃、账户版本过期或余额不足都会失败关闭，绝不回退查询数据库。其他命令
 * 在 reducer 完成前返回 UNSUPPORTED，由迁移编排器单独处理，不能伪装成已由内存状态裁决。</p>
 */
@Service
public class AccountUserStateReducer {

    private final ObjectMapper objectMapper;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionCommandLane lane;
    private final Map<UserPartitionKey, AccountUserReducerState> states = new java.util.concurrent.ConcurrentHashMap<>();

    public AccountUserStateReducer(ObjectMapper objectMapper,
                                   UserPartitionStateStore stateStore,
                                   UserPartitionCommandLane lane) {
        this.objectMapper = objectMapper;
        this.stateStore = stateStore;
        this.lane = lane;
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
