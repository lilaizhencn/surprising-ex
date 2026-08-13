package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreMessageType;
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
        aeron.command(CoreMessageType.SETTLE_INSTRUMENT,
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), 0,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                        settlementId, symbol, version, settlementPriceTicks, optionCashUnitsPerContract)));
    }

    private static Instant settlementTime(Instant deliveryTime, Instant eventTime) {
        Instant value = deliveryTime == null ? eventTime : deliveryTime;
        if (value == null || value.toEpochMilli() <= 0) {
            throw new IllegalArgumentException("settlement time must be positive");
        }
        return value;
    }
}
