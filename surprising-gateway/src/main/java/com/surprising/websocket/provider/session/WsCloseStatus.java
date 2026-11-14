package com.surprising.websocket.provider.session;

/**
 * WebSocket close status decoupled from Spring/Servlet WebSocket APIs.
 */
public record WsCloseStatus(int code, String reason) {

    public static final WsCloseStatus NORMAL = new WsCloseStatus(1000, "Normal closure");
    public static final WsCloseStatus GOING_AWAY = new WsCloseStatus(1001, "Going away");
    public static final WsCloseStatus POLICY_VIOLATION = new WsCloseStatus(1008, "Policy violation");
    public static final WsCloseStatus SERVER_ERROR = new WsCloseStatus(1011, "Server error");
    public static final WsCloseStatus SERVICE_RESTARTED = new WsCloseStatus(1012, "Service restarted");
    public static final WsCloseStatus SERVICE_OVERLOAD = new WsCloseStatus(1013, "Service overload");
    public static final WsCloseStatus SESSION_NOT_RELIABLE = new WsCloseStatus(4500, "Session not reliable");

    public WsCloseStatus withReason(String newReason) {
        return new WsCloseStatus(code, newReason);
    }
}
