package com.surprising.gateway.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class GatewayRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        try {
            hints.reflection().registerType(
                    Class.forName("com.surprising.gateway.provider.service.ProductTransferWireRequest", false, classLoader),
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Native runtime hint type not found: ProductTransferWireRequest", ex);
        }
    }
}
