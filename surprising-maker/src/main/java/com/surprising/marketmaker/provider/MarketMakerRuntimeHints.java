package com.surprising.marketmaker.provider;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.marketmaker.provider.service.InstrumentSnapshotConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class MarketMakerRuntimeHints implements RuntimeHintsRegistrar {
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
        registerRecord(hints, InstrumentEvent.class);
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        register(hints, InstrumentSnapshotConsumer.class);
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
