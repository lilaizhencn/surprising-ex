package com.surprising.trading.matching;

import com.lmax.disruptor.AbstractSequencer;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import net.openhft.chronicle.core.io.VanillaReferenceCounted;
import net.openhft.chronicle.core.util.IgnoresEverything;
import net.openhft.chronicle.wire.DocumentContext;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.StateHashReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import com.surprising.trading.matching.service.InstrumentSnapshotConsumer;
import com.surprising.trading.matching.service.MatchingAuditProjectionConsumer;
import com.surprising.trading.matching.service.MatchingCommandConsumer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class MatchingRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerRecord(hints, IndexComponentSnapshot.class);
        registerRecord(hints, IndexPriceEvent.class);
        registerRecord(hints, MarkPriceEvent.class);
        registerRecord(hints, MarkPricePublishedEvent.class);
        registerRecord(hints, PerpBookTickerEvent.class);
        registerRecord(hints, PerpFundingRateEvent.class);
        registerRecord(hints, PerpTradeEvent.class);
        register(hints, InstrumentSnapshotConsumer.class);
        register(hints, MatchingAuditProjectionConsumer.class);
        register(hints, MatchingCommandConsumer.class);
        register(hints, MarkPriceConsumerProperties.class);
        register(hints, MarkPriceKafkaConsumer.class);
        registerExchangeCoreReports(hints);
        registerAffinityProxies(hints, classLoader);
        registerDisruptorFields(hints, classLoader);
        hints.reflection().registerType(VanillaReferenceCounted.class,
                MemberCategory.ACCESS_DECLARED_FIELDS);
        registerJdkArraySupport(hints, classLoader);
        hints.proxies().registerJdkProxy(DocumentContext.class, IgnoresEverything.class);
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

    private void registerExchangeCoreReports(RuntimeHints hints) {
        for (Class<?> type : new Class<?>[]{
                BatchAddAccountsCommand.class,
                BatchAddSymbolsCommand.class,
                StateHashReportQuery.class,
                SingleUserReportQuery.class,
                TotalCurrencyBalanceReportQuery.class}) {
            hints.reflection().registerType(type,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }

    private void registerAffinityProxies(RuntimeHints hints, ClassLoader classLoader) {
        for (String typeName : new String[]{
                "net.openhft.affinity.impl.OSXJNAAffinity$CLibrary",
                "net.openhft.affinity.impl.LinuxJNAAffinity$CLibrary",
                "net.openhft.affinity.impl.PosixJNAAffinity$CLibrary"}) {
            try {
                hints.proxies().registerJdkProxy(Class.forName(typeName, false, classLoader));
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void registerDisruptorFields(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> type : new Class<?>[]{RingBuffer.class, AbstractSequencer.class, BlockingWaitStrategy.class}) {
            hints.reflection().registerType(type,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
        try {
            hints.reflection().registerType(
                    Class.forName("com.lmax.disruptor.RingBufferFields", false, classLoader),
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        } catch (ClassNotFoundException ignored) {
        }
    }

    private void registerJdkArraySupport(RuntimeHints hints, ClassLoader classLoader) {
        try {
            hints.reflection().registerType(
                    Class.forName("jdk.internal.util.ArraysSupport", false, classLoader),
                    MemberCategory.INVOKE_DECLARED_METHODS);
        } catch (ClassNotFoundException ignored) {
        }
    }
}
