package com.surprising.price.index;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.index.service.IndexPriceAuditConsumer;
import com.surprising.price.index.service.InstrumentSnapshotConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class IndexPriceRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, PriceEventType.class);
        registerRecord(hints, PricePublishedEvent.class);
        registerRecord(hints, InstrumentEvent.class);
        hints.reflection().registerType(
                IndexPriceAuditConsumer.class,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(
                InstrumentSnapshotConsumer.class,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
    }
}
