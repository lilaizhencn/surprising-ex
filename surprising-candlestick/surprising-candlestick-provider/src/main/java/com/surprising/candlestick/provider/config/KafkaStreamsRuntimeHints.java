package com.surprising.candlestick.provider.config;

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
    }
}
