package com.surprising.aeron.service.account;

import com.surprising.aeron.service.core.AccountLaneQueryTestSupport;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证 Lane 客户身份的引用计数、空行复用、冷绑定迁移和线程所有权；同数值键隔离通过直接导入模拟，不测量生产哈希碰撞概率。
 */
class LaneClientIdentitiesTest {
    /**
     * 同一身份持有两次后分别回滚及释放，检查错用户释放无效、文本清除，并让后续身份持续复用同一行及数组。
     */
    @Test void referencesProtectAnAliasUntilBothOrdersReleaseItAndRowsRecycle() throws Exception {
        // 未绑定 Lane 的独立身份行表，当前测试线程串行访问。
        var table = new LaneClientIdentities(null);
        assertThat(table.prepare(11, 7, "客户😀", true)).isEqualTo(-11);
        assertThat(table.prepare(11, 7, "客户😀", true)).isEqualTo(11);
        table.rollback(11, 7, "客户😀");
        assertThat(table.name(11, 7)).isEqualTo("客户😀");
        assertThat(table.publishedCount()).isOne();
        table.release(11, 8);
        assertThat(table.publishedCount()).isOne();
        table.release(11, 7);
        assertThat(table.publishedCount()).isZero();
        assertThat((String[]) field(table, "names")).allMatch(/* 名字数组中的一个槽位，最后释放后应为 null。 */ v -> v == null);
        // 首次回收身份后的用户数组对象，后续单行周转应复用它。
        var rows = field(table, "users");
        for (/* 本轮需要准备、读取或释放的客户身份整数键。 */ long key = 12; key < 10_000; key++) {
            assertThat(table.prepare(key, 8, "other-" + key, true)).isEqualTo(-key);
            assertThat(table.find(11, 7, "客户😀")).isZero();
            table.release(key, 8);
        }
        assertThat(field(table, "users")).isSameAs(rows);
        assertThat(field(table, "nextRow")).isEqualTo(1);
        assertThat(table.publishedCount()).isZero();
    }

    /**
     * 尝试同键不同用户或文本、错误回滚以及引用数溢出，要求原身份、数量及文本保持不变。
     */
    @Test void collisionOverflowAndWrongRollbackDoNotChangeTheExistingIdentity() throws Exception {
        // 未绑定 Lane 的独立身份行表，当前测试线程串行访问。
        var table = new LaneClientIdentities(null);
        table.prepare(17, 7, "original", true);
        assertThatThrownBy(() -> table.prepare(17, 8, "original", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table.prepare(17, 7, "different", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table.rollback(17, 7, "different")).isInstanceOf(IllegalStateException.class);
        assertThat(table.name(17, 7)).isEqualTo("original");
        assertThat(table.find(17, 8, "original")).isZero();
        ((long[]) field(table, "references"))[0] = Long.MAX_VALUE;
        assertThatThrownBy(() -> table.prepare(17, 7, "original", true)).isInstanceOf(ArithmeticException.class);
        assertThat(((long[]) field(table, "references"))[0]).isEqualTo(Long.MAX_VALUE);
        assertThat(table.publishedCount()).isOne();
    }

    /**
     * 先容纳一千零二十四个身份，再滑动删除最旧并加入新键，要求全部存活文本正确且完成初始扩容后复用名字数组。
     */
    @Test void growthAndBackShiftDeletionKeepEveryLiveIdentityAcrossWraps() throws Exception {
        // 未绑定 Lane 的独立身份行表，当前测试线程串行访问。
        var table = new LaneClientIdentities(null);
        // 滑动窗口始终保持的存活身份数量，一千零二十四。
        int live = 1024;
        for (/* 本轮需要准备、读取或释放的客户身份整数键。 */ long key = 1; key <= live; key++) table.prepare(key, 7, "id-" + key, true);
        // 完成初始增长后的名字数组对象，后续滑动窗口不应替换它。
        var names = field(table, "names");
        for (/* 本轮需要准备、读取或释放的客户身份整数键。 */ long key = 1; key <= 10_000; key++) {
            assertThat(table.name(key, 7)).isEqualTo("id-" + key);
            table.release(key, 7);
            // 本轮补入滑动窗口末端的新身份键。
            long next = key + live;
            table.prepare(next, 7, "id-" + next, true);
        }
        assertThat(field(table, "names")).isSameAs(names);
        assertThat(table.publishedCount()).isEqualTo(live);
        for (/* 本轮需要准备、读取或释放的客户身份整数键。 */ long key = 10_001; key <= 10_000 + live; key++)
            assertThat(table.name(key, 7)).isEqualTo("id-" + key);
    }

    /**
     * 把独立字典绑定旧运行时再冷迁移到新运行时，核对源清空、字典版本不变及双引用身份需要两次释放。
     */
    @Test void initialBindingAndColdRebindTransferAuthorityWithExactReferences() {
        // 被验证冷绑定与 Lane 路由规则的运行时身份字典。
        var identities = new RuntimeIdentityRegistry();
        // 用户七持有两次的第一个确定性客户身份键。
        long first = identities.prepareClientKey(7, "first");
        identities.prepareClientKey(7, "first");
        // 用户八持有一次的第二个确定性客户身份键。
        long second = identities.clientKey(8, "second");
        // 首次 Lane 绑定前记录的字典版本，冷重绑定应保持此版本。
        long version = identities.dictionaryVersion();
        try (/* 首次接收身份权威表的旧运行时，作用域结束自动关闭。 */ var oldRuntime = new TradingRuntimeState(); /* 当前测试使用的目标运行时，作用域结束关闭账户工作资源。 */ var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(oldRuntime);
            // 旧运行时中用户七的身份表，冷迁移成功后其存活数量应归零。
            var oldTable = oldRuntime.accountLane(oldRuntime.topology().accountLaneId(7)).clientIdentities;
            assertThat(identities.clientIdentityCount()).isEqualTo(2);
            identities.bindClientLanes(runtime);
            assertThat(oldTable.publishedCount()).isZero();
            assertThat(identities.dictionaryVersion()).isEqualTo(version);
            identities.releaseClientKey(7, first);
            assertThat(identities.clientOrderId(7, first)).isEqualTo("first");
            identities.releaseClientKey(7, first);
            assertThat(identities.findClientKeyValue(7, "first")).isZero();
            assertThat(identities.clientOrderId(8, second)).isEqualTo("second");
            assertThat(identities.clientIdentityCount()).isOne();
        }
    }

    /**
     * 启动 Lane 后要求 Owner 直接访问身份行失败；通过查询边界取得旧文本，再让 Lane 回收旧身份，已经返回的文本仍可安全保留。
     */
    @Test void activeOwnerCannotReadOrMutateIdentityRowsAndDelayedReadersKeepOwnedNames() {
        // 被验证冷绑定与 Lane 路由规则的运行时身份字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 当前测试使用的目标运行时，作用域结束关闭账户工作资源。 */ var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(runtime);
            // 本轮需要准备、读取或释放的客户身份整数键。
            long key = identities.prepareClientKey(7, "retained-name");
            runtime.startAccountLanes();
            assertThatThrownBy(() -> identities.findClientKeyValue(7, "retained-name"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound to another thread");
            assertThatThrownBy(() -> identities.clientOrderId(7, key)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> identities.prepareClientKey(7, "foreign-write")).isInstanceOf(IllegalStateException.class);
            // 查询边界返回并由测试保留的旧客户号文本，后续行复用不能改变它。
            String name = AccountLaneQueryTestSupport.read(runtime, 7, () -> identities.clientOrderId(7, key));
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), /* 控制回调提供的 Lane 编号，本用例通过 currentLaneCommandScope 取得实际 Lane，不读取此参数。 */ ignored -> {
                // 当前控制回调实际拥有的账户 Lane。
                var lane = runtime.currentLaneCommandScope();
                identities.releaseClientKeyInLane(lane, 7, key);
                identities.prepareClientKeyInLane(lane, 7, "next-name");
                return null;
            });
            await(runtime);
            assertThat(name).isEqualTo("retained-name");
            assertThat(identities.clientIdentityCount()).isOne();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> identities.findClientKeyValue(7, "retained-name"))).isZero();
            try (/* 用来检查活跃状态下不允许冷重绑定的另一个独立运行时。 */ var otherRuntime = new TradingRuntimeState()) {
                assertThatThrownBy(() -> identities.bindClientLanes(otherRuntime)).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    /**
     * 在两个 Lane 导入相同数值键并发访问各自身份，用门闩证明独立推进，同时拒绝同编号但不属于运行时的外来 Lane 对象。
     */
    @Test void equalNumericKeysOnDifferentLanesRemainIndependentAndForeignLaneObjectsAreRejected() {
        // 被验证冷绑定与 Lane 路由规则的运行时身份字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 当前测试使用的目标运行时，作用域结束关闭账户工作资源。 */ var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(runtime);
            // 第一个测试用户编号，作为不同 Lane 路由的参照。
            // 逐步调整直到与第一用户落到不同 Lane 的用户编号。
            long firstUser = 7, secondUser = 8;
            while (runtime.topology().accountLaneId(secondUser) == runtime.topology().accountLaneId(firstUser)) secondUser++;
            // 为并发回调固定下来的第二用户编号。
            long otherUser = secondUser;
            // 运行时真实持有的第一用户账户 Lane。
            var firstLane = runtime.accountLane(runtime.topology().accountLaneId(firstUser));
            // 运行时真实持有的第二用户账户 Lane。
            var secondLane = runtime.accountLane(runtime.topology().accountLaneId(secondUser));
            // 直接导入相同数值键模拟少见碰撞，不改变生产哈希算法。
            firstLane.clientIdentities.importIdentity(19, firstUser, "first-name");
            secondLane.clientIdentities.importIdentity(19, secondUser, "second-name");
            assertThat(identities.clientOrderId(firstUser, 19)).isEqualTo("first-name");
            assertThat(identities.clientOrderId(otherUser, 19)).isEqualTo("second-name");
            identities.releaseClientKey(firstUser, 19);
            assertThat(identities.clientOrderId(otherUser, 19)).isEqualTo("second-name");
            assertThat(identities.clientIdentityCount()).isOne();
            assertThatThrownBy(() -> identities.prepareClientKeyInLane(
                    new AccountLaneState(secondLane.laneId(), 16), otherUser, "foreign"))
                    .isInstanceOf(IllegalStateException.class);
            runtime.startAccountLanes();
            // 计数为二的并发门闩，两条 Lane 都进入后才继续，检查互不阻塞。
            var entered = new java.util.concurrent.CountDownLatch(2);
            runtime.dispatchControlLanes((1L << firstLane.laneId()) | (1L << secondLane.laneId()), /* 本次控制回调正在执行的实际 Lane 编号。 */ laneId -> {
                // 当前控制回调实际拥有的账户 Lane。
                var lane = runtime.currentLaneCommandScope();
                // 当前控制回调是否属于第一用户的 Lane。
                boolean first = laneId == firstLane.laneId();
                // 本 Lane 对应的用户编号，用来核对同数值身份键的归属。
                long uid = first ? firstUser : otherUser;
                // 当前 Lane 应独立读取和准备的客户号文本。
                String name = first ? "first-name" : "second-name";
                lane.clientIdentities.prepare(19, uid, name, true);
                entered.countDown();
                try {
                    if (!entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("the other Lane did not progress independently");
                } catch (/* 等待另一 Lane 进入时发生的中断，转为断言失败保留原原因。 */ InterruptedException failure) { throw new AssertionError(failure); }
                assertThat(identities.clientOrderId(uid, 19)).isEqualTo(name);
                identities.releaseClientKeyInLane(lane, uid, 19);
                return null;
            });
            await(runtime);
            assertThat(identities.clientIdentityCount()).isOne();
            assertThat(AccountLaneQueryTestSupport.read(runtime, otherUser,
                    () -> identities.clientOrderId(otherUser, 19))).isEqualTo("second-name");
        }
    }

    /**
     * 反射读取行表内部数组或计数，用于断言复用和溢出前后状态。
     * @param value 要反射读取内部字段的实例
     * @param name 需要读取的字段名称
     */
    private static Object field(Object value, String name) throws Exception {
        // 按名字定位并开放访问的反射 Field。
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }

    /**
     * 轮询当前运行时的控制 Lane 完成结果，等待上限五秒，超时直接令测试失败。
     * @param runtime 要等待控制 Lane 全部完成的运行时
     */
    private static void await(TradingRuntimeState runtime) {
        // 控制 Lane 轮询允许等待的单调时钟截止时间，单位为纳秒。
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() > deadline) throw new AssertionError("client identity command timed out");
            Thread.onSpinWait();
        }
    }
}
