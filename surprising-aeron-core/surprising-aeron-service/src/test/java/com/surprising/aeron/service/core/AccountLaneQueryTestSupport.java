package com.surprising.aeron.service.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 测试中按用户所属 Lane 执行只读查询的辅助器。Lane 未启动时直接运行，已启动时通过查询邮箱派发并等待结果，避免测试线程直接越界读取活动账户。
 */
public final class AccountLaneQueryTestSupport {
    /**
     * 账户 Lane 未启动就直接调用查询；已启动则进入目标 Lane 的只读范围执行，把结果或原始失败交给 Future，调用线程最多等三秒，邮箱满或等待失败都让测试报错。
     * @param <T> 本次查询结果类型，使 Supplier 和异步 Future 保持同一类型
     * @param runtime 测试查询所读取的运行时，用来判断 Lane 是否启动及派发目标查询
     * @param userId 决定目标账户 Lane 的用户编号
     * @param query 需要在目标 Lane 合法只读范围内执行的查询函数
     */
    public static <T> T read(TradingAccountRuntime runtime, long userId, Supplier<T> query) {
        if (!runtime.accountLanesStarted()) return query.get();
        // 账户 Lane 向调用测试线程交付查询结果或原始失败的异步凭据。
        var result = new CompletableFuture<T>();
        // 目标用户所属账户 Lane 的位集合，只把查询派发到正确的 Lane。
        long mask = runtime.topology().accountLaneMask(userId);
        if (!runtime.dispatchAccountQuery(mask, /* 本次查询回调对应的账户 Lane，继续用 readAccountLane 建立合法读取范围。 */ lane -> {
            try { result.complete(runtime.readAccountLane(lane, /* 只读作用域提供的 Lane 入口，本辅助器直接调用外层 query，所需读取权限已建立。 */ ignored -> query.get())); }
            catch (/* 账户 Lane 执行查询时抛出的原始失败，存入 Future 交调用线程接收。 */ Throwable failure) { result.completeExceptionally(failure); }
        })) throw new AssertionError("query mailbox is full");
        try { return result.get(3, TimeUnit.SECONDS); }
        catch (/* 调用线程等待结果时遇到的异常或超时，包装成断言失败并保留原因。 */ Exception failure) { throw new AssertionError("account Lane query failed", failure); }
    }
}
