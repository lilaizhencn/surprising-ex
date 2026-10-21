package com.surprising.aeron.service.orchestration.result;

/**
 * 测试产物中的响应描述符白盒读取入口，只暴露是否属于固定池，不接管或归还任何响应引用。
 */
public final class ResponseArenaTestAccess {
    /**
     * 工具类不保存实例状态，禁止外部创建。
     */
    private ResponseArenaTestAccess() {}
    /**
     * 直接读取当前描述符的固定池归属标记，不改变其租约或有效长度。
     * @param slot 当前有效的非空响应描述符
     */
    public static boolean arenaBacked(ResponseArena.Slot slot) { return slot.arenaBacked; }
}
