package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ExpiringContractSettlementFanoutService {

    private final AccountAeronGateway aeron;
    private final AccountProperties properties;

    public ExpiringContractSettlementFanoutService(AccountAeronGateway aeron, AccountProperties properties) {
        this.aeron = aeron;
        this.properties = properties;
    }

    public int fanout(DeliverySettlementEvent event) {
        submit(event.symbol(), event.version(), event.settlementPriceTicks(), 0,
                settlementTime(event.deliveryTime(), event.eventTime()));
        return 1;
    }

    public int fanout(OptionExerciseEvent event) {
        submit(event.symbol(), event.version(), 0, event.cashSettlementUnitsPerContract(),
                settlementTime(event.deliveryTime(), event.eventTime()));
        return 1;
    }

    private void submit(String symbol, long version, long settlementPriceTicks,
                        long optionCashUnitsPerContract, Instant settlementTime) {
        long settlementId = settlementTime.toEpochMilli();
        String identity = properties.getKafka().getProductLine() + ":lifecycle:" + symbol.toUpperCase()
                + ':' + settlementId;
        long cursor = 0;
        CoreSettlementProgressView persisted = decodeProgressOrQuery(symbol, settlementId, null);
        if (persisted != null && persisted.complete() && persisted.settlementId() == settlementId) return;
        long orderCursor = 0;
        if (persisted != null && !persisted.complete()) {
            orderCursor = persisted.ordersComplete() ? 0 : persisted.nextCursorOrderId();
            cursor = persisted.ordersComplete() ? persisted.nextCursorUserId() : 0;
        }
        for (;;) {
            UUID commandId = UUID.nameUUIDFromBytes((identity + ':' + orderCursor + ':' + cursor)
                    .getBytes(StandardCharsets.UTF_8));
            CoreResponse response = aeron.command(CoreMessageType.SETTLE_INSTRUMENT, commandId, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                            settlementId, symbol, version, settlementPriceTicks, optionCashUnitsPerContract,
                            cursor, SettleInstrumentCommand.DEFAULT_MAX_USERS, orderCursor,
                            SettleInstrumentCommand.DEFAULT_MAX_ORDERS)));
            CoreSettlementProgressView progress = decodeProgressOrQuery(symbol, settlementId, response);
            if (progress == null || progress.complete()) return;
            if (!progress.ordersComplete()) {
                if (progress.nextCursorOrderId() <= orderCursor) {
                    throw new IllegalStateException("Aeron settlement order cursor did not advance");
                }
                orderCursor = progress.nextCursorOrderId();
                cursor = 0;
            } else {
                if (progress.nextCursorUserId() <= cursor) {
                    throw new IllegalStateException("Aeron settlement user cursor did not advance");
                }
                orderCursor = 0;
                cursor = progress.nextCursorUserId();
            }
        }
    }

    private CoreSettlementProgressView decodeProgressOrQuery(String symbol, long settlementId,
                                                              CoreResponse response) {
        CoreResponse effective = response;
        if (effective == null || effective.data().length == 0) {
            try {
                effective = aeron.query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY, UUID.randomUUID(),
                        CoreStateQueryCodec.encodeSettlementProgressQuery(symbol));
            } catch (RuntimeException exception) {
                return null;
            }
        }
        if (effective == null || (effective.status() != com.surprising.aeron.protocol.ResponseStatus.OK
                && effective.commandStatus() != com.surprising.aeron.protocol.ResponseStatus.APPLIED)
                || effective.data().length == 0) return null;
        CoreSettlementProgressView progress = CoreSettlementProgressCodec.decode(effective.data());
        if (progress.settlementId() != 0 && progress.settlementId() != settlementId) {
            throw new IllegalStateException("Aeron settlement progress mismatch");
        }
        return progress;
    }

    private static Instant settlementTime(Instant deliveryTime, Instant eventTime) {
        Instant value = deliveryTime == null ? eventTime : deliveryTime;
        if (value == null || value.toEpochMilli() <= 0) {
            throw new IllegalArgumentException("settlement time must be positive");
        }
        return value;
    }
}
