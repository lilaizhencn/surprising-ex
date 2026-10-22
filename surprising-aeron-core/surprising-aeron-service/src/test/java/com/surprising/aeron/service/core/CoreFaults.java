package com.surprising.aeron.service.core;

import com.surprising.aeron.client.RealtimeOutbox;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 测试专用的真实提交故障注入入口。测试 JVM 先登记目标 Core，插桩到达相应业务边界时一次取出位图或结算后断言，直接破坏真实提交前提，让生产故障分支处理。它不替换生产资金算法；容器和观察者属于测试 JVM，全局访问未作并发保护。
 */
public final class CoreFaults {
    /** 按 Core 对象身份保存的一次性待注入 Lane 位图，真实边界取走后移除。 */
    private static final Map<Object, Long> laneMasks = new IdentityHashMap<>();
    /** 按 Core 对象身份保存的一次性结算后断言，实际运行后翻转提交位图。 */
    private static final Map<Object, Runnable> settlements = new IdentityHashMap<>();
    /** 可空激活观察者，每次观察都会通知，直到调用方替换或清空。 */
    private static Consumer<TradingCoreRuntime> activation;

    /**
     * 要求测试插桩已安装，再按 Core 对象身份登记下一次账户上下文建立后要覆盖的参与位图。
     * @param state 要登记故障或读取激活标记的目标 Core 运行时
     * @param mask 下一次边界要覆盖的参与账户位图，可故意设置为错误值
     */
    public static void laneMask(TradingCoreRuntime state, long mask) {
        requireAgent();
        laneMasks.put(state, mask);
    }

    /**
     * 要求插桩存在，按 Core 对象登记结算后断言；真实边界会取走执行，再翻转参与位图制造最终校验失败。传空时该次边界不执行注入。
     * @param state 要登记故障或读取激活标记的目标 Core 运行时
     * @param assertion 真实结算完成后先执行的断言，非空时随后破坏最终位图
     */
    public static void afterSettlement(TradingCoreRuntime state, Runnable assertion) {
        requireAgent();
        settlements.put(state, assertion);
    }

    /**
     * 要求测试插桩已安装，再保存或替换可空激活观察者；传空解除观察，观察函数等真实边界到达后才调用。
     * @param observer 可空激活观察函数，传空解除原观察者
     */
    public static void beforeActivation(Consumer<TradingCoreRuntime> observer) {
        requireAgent();
        activation = observer;
    }

    /**
     * 有观察者就把候选转换为 Core 后通知；观察者不在此自动移除，异常继续传给测试。
     * @param candidate 插桩传来的待观察 Core 对象，存在观察者时转换类型
     */
    public static void observeActivation(Object candidate) {
        if (activation != null) activation.accept((TradingCoreRuntime) candidate);
    }

    /**
     * 按插桩执行器的 owner 找到并移除待注入位图，存在时直接覆盖批次 actualLaneMask，让真实最终校验遇到错误输入。
     * @param state 插桩传入的批次执行器对象，借其 owner 字段找到原 Core
     * @param batch 真实边界正在处理的批次对象，含 actualLaneMask 字段
     */
    public static void afterLaneContext(Object state, Object batch) {
        // 为当前 Core 登记并在此边界取走的可空故障位图
        Long mask = laneMasks.remove(get(state, "owner"));
        if (mask != null) set(batch, "actualLaneMask", mask);
    }

    /**
     * 按执行器 owner 取走一次性断言，空则结束；先运行断言，再把实际账户位图最低位翻转，制造结算已发生后的提交失败。
     * @param state 插桩传入的批次执行器对象，借其 owner 字段找到原 Core
     * @param batch 真实边界正在处理的批次对象，含 actualLaneMask 字段
     */
    public static void afterSettlement(Object state, Object batch) {
        // 为当前 Core 登记并取走的结算后断言，空则不注入
        Runnable assertion = settlements.remove(get(state, "owner"));
        if (assertion == null) return;
        assertion.run();
        set(batch, "actualLaneMask", (long) get(batch, "actualLaneMask") ^ 1L);
    }

    /**
     * 把指定 outbox 交给既有 Owner 测试支撑对象挂载，继续使用其原实时边界。
     * @param service 提供既有实时挂载入口的 Owner 测试支撑对象
     * @param outbox 本测试要接入的实时输出队列
     */
    public static void attachRealtime(TradingOwnerTestSupport service, RealtimeOutbox outbox) {
        service.attachRealtime(outbox);
    }

    /**
     * 收集 Core、Matcher 和发布器的激活标记；当前 runtime 字段同样读取 Core activated，并非独立读取账户运行时标记。
     * @param state 要登记故障或读取激活标记的目标 Core 运行时
     */
    public static ActivationState activationState(TradingCoreRuntime state) {
        return new ActivationState(state.activated(),
                state.activated(),
                ((com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter)
                        get(state, "matchingAdapter")).activated(),
                state.commits.publication.activated());
    }

    /**
     * 某次观察得到的四个激活标记。当前 core 与 runtime 都取 Core activated；这个记录本身不证明账户线程已独立激活，也不持有运行资源。
     * @param core 此次捕获的 Core activated 标记
     * @param runtime 当前实现再次使用 Core activated 的观察值，不是独立账户激活标记
     * @param matcher 底层 Matcher 适配器 activated 标记
     * @param projector Owner 发布器 activated 标记
     */
    public record ActivationState(boolean core, boolean runtime, boolean matcher, boolean projector) {
        /**
         * 只有保存的四个激活标记全部为假才返回真，范围限于本记录捕获的值。
         */
        public boolean allPassive() {
            return !core && !runtime && !matcher && !projector;
        }

        /**
         * 只有保存的四个标记全部为真才返回真，不在此启动或探测工作线程。
         */
        public boolean allActivated() {
            return core && runtime && matcher && projector;
        }
    }

    /**
     * 缺少测试故障插桩时立即抛断言，防止测试登记了故障却没有实际注入。
     */
    private static void requireAgent() {
        if (!CoreFaultAgent.installed) throw new AssertionError("Surefire fault agent is required");
    }

    /**
     * 反射读取目标类直接声明的字段；受检反射异常包装为断言失败，其余运行错误继续传播。
     * @param target 要反射操作其直接声明字段的测试目标对象
     * @param name 目标类的精确字段名
     */
    private static Object get(Object target, String name) {
        try {
            // 目标类声明的私有字段，允许访问后执行测试读写
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (/* 反射查找或读写失败的受检异常，包装成断言失败 */ ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 反射写入目标类直接声明的字段；只用于测试故意破坏前提，受检反射异常包装为断言失败。
     * @param target 要反射操作其直接声明字段的测试目标对象
     * @param name 目标类的精确字段名
     * @param value 要写入的测试值，调用方保证类型匹配
     */
    private static void set(Object target, String name, Object value) {
        try {
            // 目标类声明的私有字段，允许访问后执行测试读写
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (/* 反射查找或读写失败的受检异常，包装成断言失败 */ ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
