package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 聊天持久事实的有界投影，不包含付款密文、文件私有key或Core余额。 */
public final class C2cChatRows {
    private C2cChatRows() { }
    public record Activity(long minute,long day) { }
    public record UploadUsage(long files,long bytes) { }
    public record LastMessage(long id,String text,String kind,boolean attachment,Instant createdAt) { }
    public record OrderSummary(UUID id,C2cOrderStatus status,BigDecimal fiatAmount,BigDecimal price) { }
    public record Inbox(C2cConversation conversation,String peerNickname,LastMessage lastMessage,long unread,OrderSummary order) { }
}
