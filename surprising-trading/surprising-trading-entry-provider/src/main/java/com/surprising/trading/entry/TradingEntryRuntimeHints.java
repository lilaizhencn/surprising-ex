package com.surprising.trading.entry;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.OpenInterestShardUpdatedEvent;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserAlgoChildCommand;
import com.surprising.trading.order.model.OrderUserCancelCommand;
import com.surprising.trading.order.model.OrderUserCancelOpenCommand;
import com.surprising.trading.order.model.OrderUserEvent;
import com.surprising.trading.order.model.OrderUserPruneReduceOnlyCommand;
import com.surprising.trading.order.model.OrderUserState;
import com.surprising.trading.order.model.OrderUserStateSnapshot;
import com.surprising.trading.order.service.FeeScheduleSnapshotConsumer;
import com.surprising.trading.order.service.InstrumentOrderDrainConsumer;
import com.surprising.trading.order.service.InstrumentSnapshotConsumer;
import com.surprising.trading.order.service.LeverageSettingSnapshotConsumer;
import com.surprising.trading.order.service.OpenInterestSnapshotConsumer;
import com.surprising.trading.order.service.OrderAccountCommandResultConsumer;
import com.surprising.trading.order.service.OrderAccountStateSnapshotConsumer;
import com.surprising.trading.order.service.OrderMatchResultConsumer;
import com.surprising.trading.order.service.OrderPositionMaintenanceConsumer;
import com.surprising.trading.order.service.OrderStateSnapshotConsumer;
import com.surprising.trading.order.service.OrderUserCommandConsumer;
import com.surprising.trading.order.service.OrderUserCommandResultWaiter;
import com.surprising.trading.trigger.service.InstrumentTriggerDrainConsumer;
import com.surprising.trading.trigger.service.PositionClosedTriggerConsumer;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.LeverageSettingEvent;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class TradingEntryRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("librocksdbjni-.*\\.jnilib");
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        registerRecord(hints, AccountCommandResultEvent.class);
        registerRecord(hints, AccountUserCommand.class);
        registerRecord(hints, OpenInterestShardUpdatedEvent.class);
        registerRecord(hints, OrderReleaseAccountCommand.class);
        registerRecord(hints, OrderReserveAccountCommand.class);
        registerRecord(hints, PositionUpdatedEvent.class);
        registerRecord(hints, InstrumentEvent.class);
        registerRecord(hints, InstrumentLifecycleDrainEvent.class);
        registerRecord(hints, FeeScheduleEvent.class);
        registerRecord(hints, LeverageSettingEvent.class);
        registerRecord(hints, MatchResultEvent.class);
        registerRecord(hints, MatchTradeEvent.class);
        registerRecord(hints, OrderEvent.class);
        registerRecord(hints, OrderUserCommand.class);
        registerRecord(hints, OrderUserCommandResult.class);
        registerRecord(hints, TriggerOrderUpdatedEvent.class);
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        registerRecord(hints, AlgoOrderChild.class);
        registerRecord(hints, AlgoOrderRecord.class);
        registerRecord(hints, CancelAllAfterTimer.class);
        registerRecord(hints, OrderUserAlgoChildCommand.class);
        registerRecord(hints, OrderUserCancelCommand.class);
        registerRecord(hints, OrderUserCancelOpenCommand.class);
        registerRecord(hints, OrderUserPruneReduceOnlyCommand.class);
        register(hints, FeeScheduleSnapshotConsumer.class);
        register(hints, InstrumentOrderDrainConsumer.class);
        register(hints, InstrumentSnapshotConsumer.class);
        register(hints, LeverageSettingSnapshotConsumer.class);
        register(hints, OpenInterestSnapshotConsumer.class);
        register(hints, OrderAccountCommandResultConsumer.class);
        register(hints, OrderAccountStateSnapshotConsumer.class);
        register(hints, OrderMatchResultConsumer.class);
        register(hints, OrderPositionMaintenanceConsumer.class);
        register(hints, OrderStateSnapshotConsumer.class);
        register(hints, OrderUserCommandConsumer.class);
        register(hints, OrderUserCommandResultWaiter.class);
        registerRecord(hints, OrderRecord.class);
        registerRecord(hints, OrderUserEvent.class);
        registerRecord(hints, OrderUserState.class);
        registerRecord(hints, OrderUserStateSnapshot.class);
        register(hints, InstrumentTriggerDrainConsumer.class);
        register(hints, com.surprising.trading.trigger.service.InstrumentSnapshotConsumer.class);
        register(hints, PositionClosedTriggerConsumer.class);
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
