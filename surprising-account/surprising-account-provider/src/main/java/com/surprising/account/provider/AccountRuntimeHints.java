package com.surprising.account.provider;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.account.provider.service.AccountCommandResultWaiter;
import com.surprising.account.provider.service.AccountInstrumentDrainConsumer;
import com.surprising.account.provider.service.AccountUserReducerState;
import com.surprising.account.provider.service.AccountStateProjectionConsumer;
import com.surprising.account.provider.service.AccountStateSnapshotReducerConsumer;
import com.surprising.account.provider.service.AccountUserCommandConsumer;
import com.surprising.account.provider.service.ExpiringContractSettlementConsumer;
import com.surprising.account.provider.service.InstrumentSnapshotConsumer;
import com.surprising.account.provider.service.PositionCacheProjectionConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class AccountRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        register(hints, AccountCommandResultWaiter.class);
        register(hints, AccountInstrumentDrainConsumer.class);
        registerRecord(hints, AccountUserReducerState.class);
        registerRecord(hints, AccountUserReducerState.Reservation.class);
        register(hints, AccountStateProjectionConsumer.class);
        register(hints, AccountStateSnapshotReducerConsumer.class);
        register(hints, AccountUserCommandConsumer.class);
        register(hints, ExpiringContractSettlementConsumer.class);
        register(hints, InstrumentSnapshotConsumer.class);
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        register(hints, PositionCacheProjectionConsumer.class);
    }

    private void register(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
