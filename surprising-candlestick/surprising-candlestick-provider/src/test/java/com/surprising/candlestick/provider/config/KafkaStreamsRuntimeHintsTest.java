package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.provider.aggregation.CandleAccumulator;
import com.surprising.candlestick.provider.aggregation.CandleSnapshot;
import com.surprising.trading.api.model.PublicTradeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class KafkaStreamsRuntimeHintsTest {

    @Test
    void registersEveryKafkaStreamsJsonBindingType() {
        RuntimeHints hints = new RuntimeHints();

        new KafkaStreamsRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                PublicTradeEvent.class,
                CandleUpdatedEvent.class,
                CandleAccumulator.class,
                CandleSnapshot.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                    .test(hints)).as("public constructors for %s", type.getName()).isTrue();
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                    .test(hints)).as("public methods for %s", type.getName()).isTrue();
        }
    }
}
