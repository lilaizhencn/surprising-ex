package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
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
    @Test
    void matchesInsertionOrderedSetAcrossGrowthDuplicatesAndReuse() {
        var actual = new PrimitiveLongChangeSet(0);
        var expected = new java.util.LinkedHashSet<Long>();
        var random = new java.util.Random(25620);
        for (int round = 0; round < 4; round++) {
            for (long key : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1}) {
                assertThat(actual.add(key)).isEqualTo(expected.add(key));
            }
            for (int i = 0; i < 4096; i++) {
                long key = random.nextInt(2048) - 1024;
                assertThat(actual.add(key)).isEqualTo(expected.add(key));
                assertThat(actual.contains(key)).isTrue();
            }
            assertThat(actual.toPrimitiveArray()).containsExactly(
                    expected.stream().mapToLong(Long::longValue).toArray());
            actual.clear();
            expected.clear();
            assertThat(actual).isEmpty();
        }
    }

}
