package com.surprising.risk.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class RiskRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("librocksdbjni-.*\\.jnilib");
        String[] recordTypes = {
                "com.surprising.account.api.model.AccountCommandResultEvent",
                "com.surprising.account.api.model.AccountUserCommand",
                "com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent",
                "com.surprising.account.api.model.PositionUpdatedEvent",
                "com.surprising.instrument.api.model.InstrumentEvent",
                "com.surprising.risk.api.model.LiquidationCandidateEvent",
                "com.surprising.risk.api.model.RiskAccountUpdatedEvent",
                "com.surprising.risk.api.model.RiskPositionUpdatedEvent",
                "com.surprising.trading.api.model.FeeScheduleEvent",
                "com.surprising.trading.api.model.MatchResultEvent",
                "com.surprising.risk.provider.service.RiskLocalProjectionStore$RiskProjectionBatch",
                "com.surprising.risk.provider.service.RiskLocalProjectionStore$ProjectionIds"
        };
        for (String recordType : recordTypes) {
            registerRecord(hints, classLoader, recordType);
        }
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
