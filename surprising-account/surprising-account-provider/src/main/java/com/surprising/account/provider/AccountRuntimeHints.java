package com.surprising.account.provider;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.account.api.model.BalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
import com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand;
import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.account.api.model.ProductBalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.provider.model.CachedPosition;
import com.surprising.account.provider.model.CachedPositionMargin;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.service.ExpiringContractSettlementConsumer;
import com.surprising.account.provider.service.InstrumentSnapshotConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public final class AccountRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PriceEventType.class);
        registerRecord(hints, PricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        registerRecord(hints, AccountCommandResultEvent.class);
        registerRecord(hints, AccountCommandTerminalResult.class);
        registerRecord(hints, CachedPosition.class);
        registerRecord(hints, CachedPositionMargin.class);
        registerRecord(hints, AdlTargetSettlementAccountCommand.class);
        registerRecord(hints, BalanceAdjustmentAccountCommand.class);
        registerRecord(hints, DeficitReservationAccountCommand.class);
        registerRecord(hints, ExpiringPositionSettlementAccountCommand.class);
        registerRecord(hints, FundingSettlementAccountCommand.class);
        registerRecord(hints, OrderReleaseAccountCommand.class);
        registerRecord(hints, OrderReserveAccountCommand.class);
        registerRecord(hints, PerpetualAccountStateUpdatedEvent.class);
        registerRecord(hints, PositionMarginAdjustmentRequest.class);
        registerRecord(hints, PositionModeUpdateRequest.class);
        registerRecord(hints, PositionUpdatedEvent.class);
        registerRecord(hints, DeliverySettlementEvent.class);
        registerRecord(hints, InstrumentEvent.class);
        registerRecord(hints, InstrumentLifecycleDrainEvent.class);
        registerRecord(hints, OptionExerciseEvent.class);
        registerRecord(hints, ProductBalanceAdjustmentAccountCommand.class);
        registerRecord(hints, ProductBalanceAdjustmentRequest.class);
        register(hints, ExpiringContractSettlementConsumer.class);
        register(hints, InstrumentSnapshotConsumer.class);
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        hints.reflection().registerType(
                TypeReference.of("org.springframework.boot.webmvc.WebMvcWebApplicationTypeDeducer"),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }

    private void register(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        for (Class<?> nestedType : type.getDeclaredClasses()) {
            if (nestedType.isRecord()) {
                registerRecord(hints, nestedType);
            }
        }
    }
}
