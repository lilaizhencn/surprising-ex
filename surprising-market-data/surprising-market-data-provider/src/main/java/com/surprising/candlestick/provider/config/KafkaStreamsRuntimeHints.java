package com.surprising.candlestick.provider.config;

import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.provider.aggregation.CandleAccumulator;
import com.surprising.candlestick.provider.aggregation.CandleRollupAccumulator;
import com.surprising.candlestick.provider.aggregation.CandleSnapshot;
import com.surprising.trading.api.model.PublicTradeEvent;
import org.apache.kafka.streams.processor.internals.NoOpProcessorWrapper;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class KafkaStreamsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(
                NoOpProcessorWrapper.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(
                CandlestickRocksDbConfig.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        registerJsonBinding(hints, PublicTradeEvent.class);
        registerJsonBinding(hints, CandleUpdatedEvent.class);
        registerJsonBinding(hints, CandleAccumulator.class);
        registerJsonBinding(hints, CandleRollupAccumulator.class);
        registerJsonBinding(hints, CandleSnapshot.class);
    }

    private void registerJsonBinding(RuntimeHints hints, Class<?> type) {
        hints.reflection().registerType(type,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
