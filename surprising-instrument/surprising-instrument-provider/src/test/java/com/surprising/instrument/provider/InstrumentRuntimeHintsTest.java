package com.surprising.instrument.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class InstrumentRuntimeHintsTest {

    @Test
    void registersEveryPublishedInstrumentEventType() {
        RuntimeHints hints = new RuntimeHints();

        new InstrumentRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                InstrumentEvent.class,
                InstrumentLifecycleDrainEvent.class,
                DeliverySettlementEvent.class,
                OptionExerciseEvent.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                    .test(hints)).as("public constructors for %s", type.getName()).isTrue();
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                    .test(hints)).as("public methods for %s", type.getName()).isTrue();
        }
    }
}
