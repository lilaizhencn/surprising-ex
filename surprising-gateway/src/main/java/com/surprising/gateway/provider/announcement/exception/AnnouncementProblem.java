package com.surprising.gateway.provider.announcement.exception;

/** 公告业务失败原因；HTTP状态由管理端/客户端的异常处理入口映射。 */
public class AnnouncementProblem extends RuntimeException {
    public enum Kind { INVALID_STATE, MISSING, CONFLICT, AUTH_REQUIRED, ACCESS_DENIED }
    private final Kind kind;
    public AnnouncementProblem(Kind kind,String message) { super(message);this.kind=kind; }
    public Kind kind() { return kind; }
}
