package com.surprising.trading.order;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.trading.api.model.OrderEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class OrderRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, InstrumentEvent.class);
        registerRecord(hints, OrderEvent.class);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
