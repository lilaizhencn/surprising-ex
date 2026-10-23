package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreCancelAllAfterCodec;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AeronCancelAllAfterStore {
    private final OrderAeronGateway aeron;

    public AeronCancelAllAfterStore(OrderAeronGateway aeron) {
        this.aeron = aeron;
    }

    public CancelAllAfterTimer set(long userId, String symbolScope, long countdownMillis,
                                   Instant triggerAt, Instant now) {
        command(userId, CoreCancelAllAfterCommand.set(symbolScope, countdownMillis,
                triggerAt == null ? 0 : triggerAt.toEpochMilli(), now.toEpochMilli()));
        return exact(userId, symbolScope).orElseThrow(() -> new IllegalStateException("Aeron timer write not visible"));
    }

    public List<CancelAllAfterTimer> due(Instant now, int limit) {
        return aeron.cancelAllAfterTimers(0, "", now.toEpochMilli(), limit).stream().map(this::timer).toList();
    }

    public Optional<CancelAllAfterTimer> claim(CancelAllAfterTimer timer, Instant now) {
        boolean applied = aeron.tryCommand(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, UUID.randomUUID(), timer.userId(),
                CoreCancelAllAfterCodec.encodeCommand(CoreCancelAllAfterCommand.claim(timer.symbolScope(),
                        timerRevision(timer), now.toEpochMilli())));
        return applied ? exact(timer.userId(), timer.symbolScope()) : Optional.empty();
    }

    public void complete(CancelAllAfterTimer timer, int canceledOrders, int canceledTriggerOrders, Instant now) {
        command(timer.userId(), CoreCancelAllAfterCommand.complete(timer.symbolScope(), timerRevision(timer),
                canceledOrders, canceledTriggerOrders, now.toEpochMilli()));
    }

    public void retry(CancelAllAfterTimer timer, Instant now) {
        command(timer.userId(), CoreCancelAllAfterCommand.retry(timer.symbolScope(), timerRevision(timer),
                now.toEpochMilli()));
    }

    public Optional<CancelAllAfterTimer> exact(long userId, String symbolScope) {
        return aeron.cancelAllAfterTimers(userId, symbolScope, 0, 1).stream().findFirst().map(this::timer);
    }

    private void command(long userId, CoreCancelAllAfterCommand command) {
        aeron.command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, commandId(userId, command), userId,
                CoreCancelAllAfterCodec.encodeCommand(command));
    }



    private UUID commandId(long userId, CoreCancelAllAfterCommand command) {
        String key = "CANCEL_AFTER:" + command.action() + ':' + userId + ':' + command.symbolScope()
                + ':' + command.expectedRevision() + ':' + command.updatedAtEpochMillis();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private CancelAllAfterTimer timer(CoreCancelAllAfterView view) {
        return new CancelAllAfterTimer(view.userId(), view.symbolScope(), view.countdownMillis(),
                view.status().name(), instant(view.triggerAtEpochMillis()), instant(view.updatedAtEpochMillis()),
                view.canceledOrders(), view.canceledTriggerOrders());
    }

    private long timerRevision(CancelAllAfterTimer timer) {
        CoreCancelAllAfterView view = aeron.cancelAllAfterTimers(timer.userId(), timer.symbolScope(), 0, 1).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("Aeron timer not found"));
        return view.revision();
    }

    private static Instant instant(long epochMillis) { return epochMillis == 0 ? null : Instant.ofEpochMilli(epochMillis); }
}
