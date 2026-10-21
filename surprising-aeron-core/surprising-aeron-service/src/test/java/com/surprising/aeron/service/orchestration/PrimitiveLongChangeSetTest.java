package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * 变更 ID 集合的首次出现顺序、去重、扩容和代号复用测试，并检查交出的只读列表不随原集合清空而变化；不把这些正确性循环当作吞吐或分配压测。
 */
class PrimitiveLongChangeSetTest {

    /**
     * 先加十七、三，再加重复十七，检查新增返回值及 primitive 数组、集合迭代都保留首次顺序。
     */
    @Test
    void preservesFirstTouchOrderAndRejectsDuplicates() {
        // 当前测试的 primitive 变更集合
        PrimitiveLongChangeSet values = new PrimitiveLongChangeSet();

        assertThat(values.add(17)).isTrue();
        assertThat(values.add(3)).isTrue();
        assertThat(values.add(17)).isFalse();

        assertThat(values.toPrimitiveArray()).containsExactly(17, 3);
        assertThat(values).containsExactly(17L, 3L);
    }

    /**
     * 保存一千零二十四条的独立只读结果，再清空复用集合；检查旧键不可见、新代可重新加入旧值，而且旧结果不被修改。
     */
    @Test
    void generationClearDoesNotExposeKeysFromAFormerLargeCommand() {
        // 当前测试的 primitive 变更集合
        PrimitiveLongChangeSet values = new PrimitiveLongChangeSet();
        LongStream.rangeClosed(1, 1_024).forEach(values::add);
        // 原集合清空前复制出的独立只读长整数列表
        ImmutableLongArrayList formerSnapshot = values.toImmutableList();

        values.clear();
        assertThat(values).isEmpty();
        assertThat(values.contains(1L)).isFalse();
        assertThat(values.add(1_024)).isTrue();
        assertThat(values.add(2_048)).isTrue();

        assertThat(values.toPrimitiveArray()).containsExactly(1_024, 2_048);
        assertThat(formerSnapshot).hasSize(1_024).startsWith(1L).endsWith(1_024L);
    }
    /**
     * 以 LinkedHashSet 作为顺序与去重对照，四轮覆盖零、long 两端、负值及固定种子随机重复值，逐轮核对后清空复用。
     */
    @Test
    void matchesInsertionOrderedSetAcrossGrowthDuplicatesAndReuse() {
        // 接受边界值与随机值的被测变更集合，初始预期大小零
        var actual = new PrimitiveLongChangeSet(0);
        // 提供去重返回值与插入顺序的 LinkedHashSet 对照
        var expected = new java.util.LinkedHashSet<Long>();
        // 种子二万五千六百二十的可复现随机源
        var random = new java.util.Random(25620);
        for (/* 扩容、重复追加和清空复用的当前轮次，共四轮 */ int round = 0; round < 4; round++) {
            for (long /* 本次追加的 long 边界值或随机整数 ID */ key : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1}) {
                assertThat(actual.add(key)).isEqualTo(expected.add(key));
            }
            for (/* 本轮四千零九十六次随机追加的序号 */ int i = 0; i < 4096; i++) {
                // 本次追加的 long 边界值或随机整数 ID
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
