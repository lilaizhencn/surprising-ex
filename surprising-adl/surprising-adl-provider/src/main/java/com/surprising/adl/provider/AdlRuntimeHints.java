package com.surprising.adl.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class AdlRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, classLoader, "com.surprising.account.api.model.AccountCommandResultEvent");
        registerRecord(hints, classLoader, "com.surprising.account.api.model.AccountUserCommand");
        registerRecord(hints, classLoader, "com.surprising.instrument.api.model.InstrumentEvent");
    }

    private void registerRecord(RuntimeHints hints, ClassLoader classLoader, String className) {
        try {
            hints.reflection().registerType(Class.forName(className, false, classLoader),
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Native runtime hint type not found: " + className, ex);
        }
    }
}
