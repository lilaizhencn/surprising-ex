package com.surprising.price.mark;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.mark.service.InstrumentSnapshotConsumer;
import com.surprising.price.mark.service.MarkPriceAuditConsumer;
import com.surprising.price.mark.service.MarkPriceService;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class MarkPriceRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        registerRecord(hints, InstrumentEvent.class);
        register(hints, InstrumentSnapshotConsumer.class);
        register(hints, MarkPriceAuditConsumer.class);
        register(hints, MarkPriceService.class);
        register(hints, MarkPriceConsumerProperties.class);
    }

    private void register(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
    }
}
