package com.surprising.aeron.service.orchestration.result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查固定响应槽、独立数组和两类引用的生命周期，以及并发借槽不共享数组。并发场景使用普通四线程池，不是启动实际 Account Lane；各用例只断言其实际调用覆盖的引用类别。
 */
class ResponseArenaTest {
    /**
     * 同一固定槽增加第二份发送引用，先归还一份仍不能复用；全部发送引用归还后描述符和 writer 可复用，writer 位置与容量限制重置。
     */
    @Test
    void writerIsReusedOnlyAfterTheResponseStorageLeaseIsReturned() {
        // 本场景具有明确槽数和每槽容量的实际响应池
        var arena = new ResponseArena(1, 16);
        // 第一次借到的固定槽数组或描述符
        var first = arena.acquireSlot(8);
        // 固定描述符复用的 ByteBuffer，只有借用期内访问
        var writer = first.writer();
        writer.putLong(123);
        arena.retain(first.storage);
        arena.release(first);
        // 原固定槽仍被保留时取得的独立描述符
        var other = arena.acquireSlot(8);
        assertThat(other.writer()).isNotSameAs(writer);
        assertThat(writer.getLong(0)).isEqualTo(123);
        arena.release(first);
        // 原引用全部归还后重新借到的固定数组或描述符
        var reused = arena.acquireSlot(8);
        assertThat(reused).isSameAs(first);
        assertThat(reused.writer()).isSameAs(writer);
        assertThat(writer.position()).isZero();
        assertThat(writer.limit()).isEqualTo(16);
        arena.release(reused);
        arena.release(other);
    }

    /**
     * 借满两个固定槽后再申请，只能得到精确长度独立数组；归还后复用原数组，清池后也可继续借原固定槽。
     */
    @Test
    void releasesAndReusesBoundedSlotsWithoutGrowingForCommonResponses() {
        // 本场景具有明确槽数和每槽容量的实际响应池
        ResponseArena arena = new ResponseArena(2, 16);
        // 第一次借到的固定槽数组或描述符
        byte[] first = arena.acquire(8);
        // 第二个尚未归还的固定数组
        byte[] second = arena.acquire(12);
        // 两个固定槽都已借出时取得的独立精确长度数组
        byte[] fallback = arena.acquire(8);

        assertThat(first).hasSize(16);
        assertThat(second).hasSize(16);
        assertThat(fallback).hasSize(8);

        arena.release(first);
        // 原引用全部归还后重新借到的固定数组或描述符
        byte[] reused = arena.acquire(8);
        assertThat(reused).isSameAs(first);
        arena.release(second);
        arena.release(reused);
        arena.clear();
        assertThat(arena.acquire(12)).isIn(first, second);
    }

    /**
     * 超过单槽容量的响应使用精确长度独立数组，归还它不占用或释放固定槽，随后正常长度仍可借池。
     */
    @Test
    void largeResponsesOwnExactStorageWithoutConsumingPooledSlots() {
        // 本场景具有明确槽数和每槽容量的实际响应池
        ResponseArena arena = new ResponseArena(1, 16);
        // 本轮需要检查或归还的响应字节数组
        byte[] response = arena.acquire(17);
        assertThat(response).hasSize(17);
        arena.release(response);
        assertThat(arena.acquire(16)).isNotSameAs(response);
    }

    /**
     * 为池响应增加真实账本引用并归还发送引用，池槽仍被保留；这时普通和超大独立描述符都不覆盖原内容，账本释放后原池描述符才复用。
     */
    @Test
    void dedicatedDescriptorsStayIndependentOfRetainedPooledResponses() {
        // 本场景具有明确槽数和每槽容量的实际响应池
        var arena = new ResponseArena(1, 16);
        // 本场景被真实账本引用保留的固定池描述符
        var pooled = arena.acquireSlot(8);
        pooled.writer().putLong(123);
        arena.retainLedger(pooled.storage);
        arena.release(pooled);
        // 固定槽仍被保留时新建的普通长度独立描述符
        var dedicated = arena.acquireSlot(8);
        // 超过固定容量的新建独立描述符
        var large = arena.acquireSlot(17);
        assertThat(dedicated.arenaBacked).isFalse();
        assertThat(large.arenaBacked).isFalse();
        assertThat(dedicated.storage).hasSize(8).isNotSameAs(pooled.storage);
        assertThat(large.storage).hasSize(17);
        dedicated.writer().putLong(456);
        assertThat(pooled.writer().getLong(0)).isEqualTo(123);
        arena.releaseLedger(pooled.storage);
        // 原引用全部归还后重新借到的固定数组或描述符
        var reused = arena.acquireSlot(8);
        assertThat(reused).isSameAs(pooled);
        assertThat(dedicated.writer().getLong(0)).isEqualTo(456);
        arena.release(dedicated);
        arena.release(large);
        arena.release(reused);
    }

    /**
     * 本用例实际用 retain 建立两份发送引用，逐份释放后才复用；没有调用 retainLedger，不能单凭此用例宣称覆盖账本计数。
     */
    @Test
    void keepsSlotLeasedUntilLedgerAndTransportReferencesAreReleased() {
        // 本场景具有明确槽数和每槽容量的实际响应池
        ResponseArena arena = new ResponseArena(1, 16);
        // 本轮需要检查或归还的响应字节数组
        byte[] response = arena.acquire(8);
        arena.retain(response);

        arena.release(response);
        // 原固定槽还有发送引用时申请到的独立数组
        byte[] stillLeased = arena.acquire(8);
        assertThat(stillLeased).hasSize(8).isNotSameAs(response);

        arena.release(response);
        assertThat(arena.acquire(8)).isSameAs(response);
    }

    /**
     * 四个普通执行线程同时申请六十四个固定槽，要求所有返回数组引用互不相同；全部任务完成后归还数组，finally 关闭执行器。
     */
    @Test
    void concurrentLaneClaimsNeverShareAResponseSlot() throws Exception {
        // 本场景具有明确槽数和每槽容量的实际响应池
        ResponseArena arena = new ResponseArena(64, 16);
        // 模拟四个并发编码方的普通执行器，不是生产 Lane 线程
        ExecutorService lanes = Executors.newFixedThreadPool(4);
        try {
            // 六十四个只申请响应数组的并发任务
            List<Callable<byte[]>> claims = new ArrayList<>();
            for (/* 本轮建立并发申请任务的计数 */ int index = 0; index < 64; index++) claims.add(() -> arena.acquire(8));
            // 等待所有申请完成后收集的数组引用列表
            List<byte[]> responses = new ArrayList<>();
            for (var /* invokeAll 返回的单个已完成申请任务，用 get 取数组 */ future : lanes.invokeAll(claims)) responses.add(future.get());
            assertThat(new HashSet<>(responses)).hasSize(64);
            for (byte[] /* 本轮需要检查或归还的响应字节数组 */ response : responses) arena.release(response);
        } finally {
            lanes.shutdownNow();
        }
    }
}
