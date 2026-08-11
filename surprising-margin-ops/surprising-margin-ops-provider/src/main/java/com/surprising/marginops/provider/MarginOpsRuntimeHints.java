package com.surprising.marginops.provider;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.funding.provider.service.FundingLocalSettlementStore;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.service.RiskLocalProjectionStore;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class MarginOpsRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, CachedRiskGroup.class);
        registerRecord(hints, CachedRiskPosition.class);
        registerRecord(hints, RiskGroupKey.class);
        registerRecord(hints, FundingPaymentCandidate.class);
        registerRecord(hints, FundingPaymentCursor.class);
        registerRecord(hints, FundingSettlementWork.class);
        registerRecord(hints, FundingLocalSettlementStore.PendingPayment.class);
        registerRecord(hints, FundingLocalSettlementStore.ProjectionSnapshot.class);
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        registerRecord(hints, AccountCommandResultEvent.class);
        registerRecord(hints, AccountUserCommand.class);
        registerRecord(hints, LiquidationFeeSettledEvent.class);
        registerRecord(hints, PerpetualAccountStateUpdatedEvent.class);
        registerRecord(hints, PositionUpdatedEvent.class);
        registerRecord(hints, InstrumentEvent.class);
        registerRecord(hints, CalculatedPositionRisk.class);
        registerRecord(hints, LiquidationCandidateEvent.class);
        registerRecord(hints, RiskAccountUpdatedEvent.class);
        registerRecord(hints, RiskPositionUpdatedEvent.class);
        registerRecord(hints, FeeScheduleEvent.class);
        registerRecord(hints, MatchResultEvent.class);
        registerRecord(hints, MatchTradeEvent.class);
        registerRecord(hints, RiskLocalProjectionStore.RiskProjectionBatch.class);
        registerRecord(hints, RiskLocalProjectionStore.RiskProjectionGroup.class);
        registerRecord(hints, RiskLocalProjectionStore.RiskProjectionPosition.class);
        registerRecord(hints, RiskLocalProjectionStore.ProjectionIds.class);
        registerRecord(hints, RiskLocalProjectionStore.PendingBatch.class);
        registerRecordByName(hints, classLoader, "com.surprising.funding.provider.service.FundingLocalSettlementStore$CommandIndex");
        registerRecordByName(hints, classLoader, "com.surprising.funding.provider.service.FundingLocalSettlementStore$SettlementRecord");
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        register(hints, com.surprising.adl.provider.service.AdlRiskPositionConsumer.class);
        register(hints, com.surprising.adl.provider.service.InstrumentSnapshotConsumer.class);
        register(hints, com.surprising.funding.provider.service.FundingAccountCommandResultConsumer.class);
        register(hints, com.surprising.funding.provider.service.FundingAccountStateSnapshotConsumer.class);
        register(hints, com.surprising.funding.provider.service.FundingRateKafkaConsumer.class);
        register(hints, com.surprising.funding.provider.service.InstrumentSnapshotConsumer.class);
        register(hints, com.surprising.insurance.provider.service.InstrumentSnapshotConsumer.class);
        register(hints, com.surprising.insurance.provider.service.LiquidationFeeEventConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.AccountStateSnapshotConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.FeeScheduleSnapshotConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.InstrumentSnapshotConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.LiquidationCandidateConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.LiquidationMatchResultConsumer.class);
        register(hints, com.surprising.liquidation.provider.service.PositionSnapshotConsumer.class);
        register(hints, com.surprising.risk.provider.service.AccountStateSnapshotConsumer.class);
        register(hints, com.surprising.risk.provider.service.InstrumentSnapshotConsumer.class);
        register(hints, com.surprising.risk.provider.service.PositionRiskTriggerConsumer.class);
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

    private void registerRecordByName(RuntimeHints hints, ClassLoader classLoader, String className) {
        try {
            hints.reflection().registerType(Class.forName(className, false, classLoader),
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Native runtime hint type not found: " + className, ex);
        }
    }
}
