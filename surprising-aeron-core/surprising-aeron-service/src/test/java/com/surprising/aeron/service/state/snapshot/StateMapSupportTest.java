package com.surprising.aeron.service.state.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态 Map 的增量、冻结血缘和延迟取值测试。核对变化键、旧表不变、父版本身份及三十二个异步读者共享一次成功加载；部分名称涉及节点或压缩，但实际断言范围以各方法说明为准，不当作分配或吞吐测量。
 */
class StateMapSupportTest {

    /**
     * 无变化键时要求直接返回同一冻结基表，且加载器一次也不调用。
     */
    @Test
    void emptyDeferredDeltaReusesTheFrozenBaseWithoutLoadingValues() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        // 加载器实际调用次数的原子计数。
        AtomicInteger loads = new AtomicInteger();

        // 延迟增量入口或无变化时直接复用的原冻结表。
        NavigableMap<Long, String> result = StateMapSupport.deferredDelta(
                base, Set.of(), Set.of(), /* 延迟加载器收到的变化键，本回调返回固定测试对象而不读取键。 */ ignored -> {
                    loads.incrementAndGet();
                    return "unexpected";
                });

        assertThat(result).isSameAs(base);
        assertThat(loads).hasValue(0);
    }

    /**
     * 发起三十二个可并发异步读请求，各自在五秒内取得同一业务对象，再检查条目与加载次数为一；没有人为屏障保证所有读者同时进入加载竞争。
     */
    @Test
    void deferredDeltaResolvesOneSharedValueAcrossConcurrentReaders() throws Exception {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, Object> base = StateMapSupport.freezeSorted(new TreeMap<>());
        // 加载器实际调用次数的原子计数。
        AtomicInteger loads = new AtomicInteger();
        // 所有异步读者都应取得的同一对象引用。
        Object expected = new Object();
        // 延迟增量入口或无变化时直接复用的原冻结表。
        NavigableMap<Long, Object> result = StateMapSupport.deferredDelta(
                base, new TreeSet<>(Set.of(7L)), new TreeSet<>(Set.of(7L)), /* 延迟加载器收到的变化键，本回调返回固定测试对象而不读取键。 */ ignored -> {
                    loads.incrementAndGet();
                    return expected;
                });

        // 保存三十二个异步读请求的 future，随后逐个设置五秒等待上限。
        List<CompletableFuture<Object>> readers = new ArrayList<>();
        for (/* 当前发起异步读请求的零起点序号。 */ int index = 0; index < 32; index++) {
            readers.add(CompletableFuture.supplyAsync(() -> result.get(7L)));
        }
        for (CompletableFuture<Object> /* 当前等待完成并核对对象身份的异步读取 future。 */ reader : readers) {
            assertThat(reader.get(5, TimeUnit.SECONDS)).isSameAs(expected);
        }
        assertThat(result.entrySet()).containsExactly(Map.entry(7L, expected));
        assertThat(loads).hasValue(1);
    }

    /**
     * 在新增量上更新、新增和删除键，核对键序、业务值、旧基表不变及变化键集合，最后检查只读外壳拒绝 put。
     */
    @Test
    void deltaMapSharesBaseAndKeepsDeterministicOrder() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one", 2L, "two", 3L, "three"));
        // 从基表建立、用于当前场景更新的可写增量入口。
        NavigableMap<Long, String> delta = StateMapSupport.delta(base);

        delta.put(2L, "updated");
        delta.put(4L, "four");
        delta.remove(1L);

        assertThat(new ArrayList<>(delta.keySet())).containsExactly(2L, 3L, 4L);
        assertThat(delta).containsEntry(2L, "updated").containsEntry(3L, "three").containsEntry(4L, "four");
        assertThat(base).containsExactly(Map.entry(1L, "one"), Map.entry(2L, "two"), Map.entry(3L, "three"));
        assertThat(StateMapSupport.changedKeys(delta)).containsExactly(1L, 2L, 4L);

        // 完成当前增量后创建的只读外壳。
        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(delta);
        assertThat(StateMapSupport.isDelta(frozen)).isTrue();
        assertThatThrownBy(() -> frozen.put(5L, "five"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 未冻结的第一代只记录键一，第二代只记录本代键二和三，验证分代变化日志。
     */
    @Test
    void nestedDeltaTracksOnlyItsOwnChanges() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one", 2L, "two"));
        // 在基表上创建的第一代可写增量。
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(1L, "uno");
        // 以第一代为基表创建的第二代可写增量。
        NavigableMap<Long, String> second = StateMapSupport.delta(first);
        second.put(3L, "three");
        second.remove(2L);

        assertThat(StateMapSupport.changedKeys(first)).containsExactly(1L);
        assertThat(StateMapSupport.changedKeys(second)).containsExactly(2L, 3L);
    }

    /**
     * 冻结第二代后要求合并两代变化、直接血缘回到原基表，强父引用标记为 false 且内容正确；本用例没有触发 GC 来测对象实际回收。
     */
    @Test
    void freezingDeltaMergesNestedChangesAndReleasesTheStrongParent() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one", 2L, "two")));
        // 在基表上创建的第一代可写增量。
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(1L, "uno");
        // 以第一代为基表创建的第二代可写增量。
        NavigableMap<Long, String> second = StateMapSupport.delta(first);
        second.put(3L, "three");

        assertThat(StateMapSupport.retainsStrongDeltaBase(second)).isTrue();

        // 完成当前增量后创建的只读外壳。
        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(second);

        assertThat(StateMapSupport.changedKeys(frozen)).containsExactly(1L, 3L);
        assertThat(StateMapSupport.isDirectDeltaOf(base, frozen)).isTrue();
        assertThat(StateMapSupport.retainsStrongDeltaBase(frozen)).isFalse();
        assertThat(frozen).containsExactly(Map.entry(1L, "uno"), Map.entry(2L, "two"),
                Map.entry(3L, "three"));
    }

    /**
     * 写入相等业务值后检查返回原值、变化键为空且内容相同；没有直接观测投影节点或对象分配次数。
     */
    @Test
    void equalValuePutDoesNotCreateAChangedKeyOrAProjectionNode() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one", 2L, "two")));
        // 从基表建立、用于当前场景更新的可写增量入口。
        NavigableMap<Long, String> delta = StateMapSupport.delta(base);

        assertThat(delta.put(1L, "one")).isEqualTo("one");

        assertThat(StateMapSupport.changedKeys(delta))
                .as("a no-op update must not enter the typed projection journal")
                .isEmpty();
        assertThat(delta).containsExactlyEntriesOf(base);
    }

    /**
     * 分别冻结两代，检查自己和直接父身份成立、更早祖先及内容相等的新 TreeMap 不算直接父关系。
     */
    @Test
    void directDeltaLineageRequiresTheImmediateParent() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        // 在基表上创建的第一代可写增量。
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(2L, "two");
        // 冻结第一代后的只读状态边界。
        NavigableMap<Long, String> frozenFirst = StateMapSupport.freezeSorted(first);
        // 以第一代为基表创建的第二代可写增量。
        NavigableMap<Long, String> second = StateMapSupport.delta(frozenFirst);
        second.put(3L, "three");
        // 冻结第二代后的只读状态边界。
        NavigableMap<Long, String> frozenSecond = StateMapSupport.freezeSorted(second);

        assertThat(StateMapSupport.isDirectDeltaOf(base, base)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(base, frozenFirst)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(frozenFirst, frozenSecond)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(base, frozenSecond)).isFalse();
        assertThat(StateMapSupport.isDirectDeltaOf(base, new TreeMap<>(base))).isFalse();
    }

    /**
     * 检查多代关系可识别原基表及中间祖先，独立内容副本不被误认为同一血缘。
     */
    @Test
    void descendantDeltaLineageIncludesEarlierParents() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        // 在基表上创建的第一代可写增量。
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(2L, "two");
        // 以第一代为基表创建的第二代可写增量。
        NavigableMap<Long, String> second = StateMapSupport.delta(StateMapSupport.freezeSorted(first));
        second.put(3L, "three");

        assertThat(StateMapSupport.isDeltaDescendantOf(base, second)).isTrue();
        assertThat(StateMapSupport.isDeltaDescendantOf(first, second)).isTrue();
        assertThat(StateMapSupport.isDeltaDescendantOf(base, new TreeMap<>(second))).isFalse();
    }

    /**
     * 用计数业务值记录 equals 调用，清零后验证合法增量关系应直接按血缘通过，业务值比较次数保持零。
     */
    @Test
    void deltaLineageValidationDoesNotCompareEveryStateValue() {
        // 记录 CountingValue.equals 调用次数的共享原子计数器。
        AtomicInteger comparisons = new AtomicInteger();
        // 基表原业务值，和后续替换值共用相等性计数器。
        CountingValue original = new CountingValue("before", comparisons);
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, CountingValue> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, original)));
        // 从基表建立、用于当前场景更新的可写增量入口。
        NavigableMap<Long, CountingValue> delta = StateMapSupport.delta(base);
        delta.put(1L, new CountingValue("after", comparisons));
        comparisons.set(0);

        StateMapSupport.requireDeltaLineage(base, delta, "users");

        assertThat(comparisons).as("delta lineage must be checked before full map equality").hasValue(0);
    }

    /**
     * 连续创建三百代并每代增加一键，最终要求键三百零一保留末次值；未设置压缩观测点或测量内存/分配，不单凭方法名证明没有压缩。
     */
    @Test
    void persistentPathCopiesKeepValuesWithoutPeriodicCompaction() {
        // 当前场景按键排序的旧状态基表，变化应用在另建增量上。
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one"));
        // 循环当前最后一代状态，下一轮从该代建立新树版本。
        NavigableMap<Long, String> previous = base;
        for (/* 当前新增版本的零起点序号，累计执行三百轮。 */ int index = 0; index < 300; index++) {
            // 本轮由前一代创建并加入一条新键值的增量，随后成为下一轮基表。
            NavigableMap<Long, String> next = StateMapSupport.delta(previous);
            next.put((long) index + 2, "value-" + index);
            previous = next;
        }

        assertThat(previous.get(301L)).isEqualTo("value-299");
    }

    /**
     * 用于观察全表值比较的测试业务值：文本决定相等和哈希，外部原子计数器只观察 equals 调用，不属于业务相等性。
     * @param value 用于内容相等性判断的业务文本
     * @param comparisons 该值记录 equals 调用次数的计数器
     */
    private record CountingValue(String value, AtomicInteger comparisons) {
        /**
         * 每次调用先增加本对象持有的比较计数，再按业务文本比较同类型值；计数器引用本身不参与相等性。
         * @param other 本次被比较对象
         */
        @Override
        public boolean equals(Object other) {
            comparisons.incrementAndGet();
            return other instanceof CountingValue /* 已确认类型的另一个 CountingValue，仅取其业务文本作比较。 */ that && value.equals(that.value);
        }

        /**
         * 只按业务文本返回哈希，不增加比较计数。
         */
        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
