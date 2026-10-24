package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreCancelAllAfterCodec;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreMessageType;
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

    public CoreCancelAllAfterView set(long userId, String symbolScope, long countdownMillis,
                                   Instant now) {
        command(userId, CoreCancelAllAfterCommand.set(symbolScope, countdownMillis, now.toEpochMilli()));
        return exact(userId, symbolScope).orElseThrow(() -> new IllegalStateException("Aeron timer write not visible"));
    }

    public List<CoreCancelAllAfterView> due(Instant now, int limit) {
        return aeron.cancelAllAfterTimers(0, "", now.toEpochMilli(), limit);
    }

    public Optional<CoreCancelAllAfterView> claim(CoreCancelAllAfterView timer, Instant now) {
        boolean applied = aeron.tryCommand(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, UUID.randomUUID(), timer.userId(),
                CoreCancelAllAfterCodec.encodeCommand(CoreCancelAllAfterCommand.claim(timer.symbolScope(),
                        timer.revision(), now.toEpochMilli())));
        if (!applied) return Optional.empty();
        long claimedRevision = Math.incrementExact(timer.revision());
        return exact(timer.userId(), timer.symbolScope())
                .filter(value -> value.revision() == claimedRevision
                        && value.status() == CoreCancelAllAfterStatus.TRIGGERING);
    }

    public void complete(CoreCancelAllAfterView timer, Instant now) {
        command(timer.userId(), CoreCancelAllAfterCommand.complete(timer.symbolScope(), timer.revision(),
                now.toEpochMilli()));
    }

    public void retry(CoreCancelAllAfterView timer, Instant now) {
        command(timer.userId(), CoreCancelAllAfterCommand.retry(timer.symbolScope(), timer.revision(),
                now.toEpochMilli()));
    }

    private Optional<CoreCancelAllAfterView> exact(long userId, String symbolScope) {
        return aeron.cancelAllAfterTimers(userId, symbolScope, 0, 1).stream().findFirst();
    }

    private void command(long userId, CoreCancelAllAfterCommand command) {
        aeron.command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, commandId(userId, command), userId,
                CoreCancelAllAfterCodec.encodeCommand(command));
    }

    private UUID commandId(long userId, CoreCancelAllAfterCommand command) {
        String key = "CANCEL_AFTER:" + command.action() + ':' + userId + ':' + command.symbolScope()
                + ':' + command.countdownMillis() + ':' + command.expectedRevision() + ':' + command.updatedAtEpochMillis();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

}
