package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PrimitiveLongChangeSetTest {

    @Test
    void preservesFirstTouchOrderAndRejectsDuplicates() {
        PrimitiveLongChangeSet values = new PrimitiveLongChangeSet();

        assertThat(values.add(17)).isTrue();
        assertThat(values.add(3)).isTrue();
        assertThat(values.add(17)).isFalse();

        assertThat(values.toPrimitiveArray()).containsExactly(17, 3);
        assertThat(values).containsExactly(17L, 3L);
    }

    @Test
    void generationClearDoesNotExposeKeysFromAFormerLargeCommand() {
        PrimitiveLongChangeSet values = new PrimitiveLongChangeSet();
        LongStream.rangeClosed(1, 1_024).forEach(values::add);
        ImmutableLongArrayList formerSnapshot = values.toImmutableList();

        values.clear();
        assertThat(values).isEmpty();
        assertThat(values.contains(1L)).isFalse();
        assertThat(values.add(1_024)).isTrue();
        assertThat(values.add(2_048)).isTrue();

        assertThat(values.toPrimitiveArray()).containsExactly(1_024, 2_048);
        assertThat(formerSnapshot).hasSize(1_024).startsWith(1L).endsWith(1_024L);
    }
}
