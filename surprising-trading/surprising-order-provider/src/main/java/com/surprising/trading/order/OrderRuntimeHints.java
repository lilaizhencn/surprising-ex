package com.surprising.trading.order;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.eventstore.UserStateChangelog;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import com.surprising.trading.order.model.OrderUserCancelCommand;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class OrderRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("librocksdbjni-.*\\.jnilib");
        registerRecord(hints, AccountCommandResultEvent.class);
        registerRecord(hints, AccountUserCommand.class);
        registerRecord(hints, OrderReserveAccountCommand.class);
        registerRecord(hints, OrderReleaseAccountCommand.class);
        registerRecord(hints, InstrumentEvent.class);
        registerRecord(hints, MatchResultEvent.class);
        registerRecord(hints, OrderEvent.class);
        registerRecord(hints, OrderUserCommand.class);
        registerRecord(hints, OrderUserCommandResult.class);
        registerRecord(hints, UserStateChangelog.class);
        registerRecord(hints, CancelAllAfterTimer.class);
        registerRecord(hints, OrderUserCancelCommand.class);
    }

    private void registerRecord(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
