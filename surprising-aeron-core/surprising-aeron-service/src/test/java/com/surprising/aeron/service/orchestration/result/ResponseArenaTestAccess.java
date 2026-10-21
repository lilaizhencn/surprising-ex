package com.surprising.aeron.service.orchestration.result;

/** 响应缓冲租约的白盒断言入口，仅在测试产物中存在。 */
public final class ResponseArenaTestAccess {
    private ResponseArenaTestAccess() {}
    public static boolean arenaBacked(ResponseArena.Slot slot) { return slot.arenaBacked; }
}
