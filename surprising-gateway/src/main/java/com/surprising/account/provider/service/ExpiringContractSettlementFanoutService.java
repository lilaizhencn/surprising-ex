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
        submit(event.instrumentId(), event.settlementPriceTicks(),
                settlementTime(event.deliveryTime(), event.eventTime()));
        return 1;
    }

    public int fanout(OptionExerciseEvent event) {
        var instrument = event.instrument();
        if (instrument == null || instrument.priceTickUnits() <= 0) {
            throw new IllegalArgumentException("option exercise snapshot with price precision is required");
        }
        long units = event.underlyingSettlementPriceUnits();
        if (units % instrument.priceTickUnits() != 0) {
            throw new IllegalArgumentException("option settlement price is not aligned to its tick");
        }
        // Core 的 strike 与价格均使用期权档位，事件的标的价格则是报价资产整数单位。
        // 只在接入边界换算；收益仍由 Core 独立计算，不能用事件现金字段覆盖交易状态。
        submit(event.instrumentId(), units / instrument.priceTickUnits(),
                settlementTime(event.deliveryTime(), event.eventTime()));
        return 1;
    }

    private void submit(String instrumentId, long settlementPriceTicks,
                        Instant settlementTime) {
        long settlementId = settlementTime.toEpochMilli();
        String identity = properties.getKafka().getProductLine() + ":lifecycle:" + instrumentId.toUpperCase()
                + ':' + settlementId + ':' + UUID.randomUUID();
        long cursor = 0;
        CoreSettlementProgressView persisted = decodeProgressOrQuery(instrumentId, settlementId, null);
        if (persisted != null && persisted.complete() && persisted.settlementId() != 0) return;
        long orderCursor = 0;
        if (persisted != null && !persisted.complete()) {
            orderCursor = persisted.ordersComplete() ? 0 : persisted.nextCursorOrderId();
            cursor = persisted.ordersComplete() ? persisted.nextCursorUserId() : 0;
        }
        for (;;) {
            UUID commandId = UUID.nameUUIDFromBytes((identity + ':' + orderCursor + ':' + cursor)
                    .getBytes(StandardCharsets.UTF_8));
            CoreResponse response = aeron.command(CoreMessageType.SETTLE_INSTRUMENT, commandId, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(settlementId, instrumentId,
                            settlementPriceTicks, cursor, SettleInstrumentCommand.DEFAULT_MAX_USERS, orderCursor,
                            SettleInstrumentCommand.DEFAULT_MAX_ORDERS)));
            if (response == null) throw new IllegalStateException("Aeron settlement response missing");
            CoreSettlementProgressView progress = decodeProgressOrQuery(instrumentId, settlementId, response);
            if (progress.complete()) return;
            if (progress.requiredInsuranceUnits() > 0) {
                throw new IllegalStateException("settlement awaits insurance: required=" + progress.requiredInsuranceUnits());
            }
            if (!progress.ordersComplete()) {
                if (progress.nextCursorOrderId() <= 0 || orderCursor != 0 && progress.nextCursorOrderId() >= orderCursor) {
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

    private CoreSettlementProgressView decodeProgressOrQuery(String instrumentId, long settlementId,
                                                              CoreResponse response) {
        CoreResponse effective = response;
        if (response != null && response.commandStatus() != com.surprising.aeron.protocol.ResponseStatus.APPLIED) {
            throw new IllegalStateException("Aeron settlement command rejected: " + response.resultCode());
        }
        if (effective == null || effective.data().length == 0) {
            effective = aeron.query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY, UUID.randomUUID(),
                    CoreStateQueryCodec.encodeSettlementProgressQuery(instrumentId));
        }
        if (effective == null || (effective.status() != com.surprising.aeron.protocol.ResponseStatus.OK
                && effective.commandStatus() != com.surprising.aeron.protocol.ResponseStatus.APPLIED)
                || effective.data().length == 0) {
            throw new IllegalStateException("Aeron settlement progress unavailable or rejected");
        }
        CoreSettlementProgressView progress = CoreSettlementProgressCodec.decode(effective.data());
        if (progress.settlementId() != 0 && progress.settlementId() != settlementId) {
            // An early administrative clearance replaces the later scheduled expiry, only after Core closes it.
            if (response == null && progress.complete()) {
                var gateResponse = aeron.query(CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, UUID.randomUUID(),
                        com.surprising.aeron.protocol.CoreMaintenanceCodec.encodeQuery(
                                new com.surprising.aeron.protocol.CoreMaintenanceCodec.Query(instrumentId, 0, 1)));
                if (gateResponse == null || gateResponse.status() != com.surprising.aeron.protocol.ResponseStatus.OK) {
                    throw new IllegalStateException("Aeron maintenance state unavailable");
                }
                var gate = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodePage(gateResponse.data()).state();
                if (gate.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED
                        && gate.taskId() == progress.settlementId()) return progress;
            }
            throw new IllegalStateException("Aeron settlement progress mismatch");
        }
        if (response != null && progress.settlementId() != settlementId) {
            throw new IllegalStateException("Aeron settlement command has no persisted progress");
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
