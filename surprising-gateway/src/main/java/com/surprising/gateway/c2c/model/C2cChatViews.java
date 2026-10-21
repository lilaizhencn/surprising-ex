package com.surprising.gateway.c2c.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** int64按既有字符串协议序列化，进程内仍使用原生游标，发送确认不再反解HTTP字段。 */
public final class C2cChatViews {
    private C2cChatViews() { }
    public record Message(@JsonFormat(shape=JsonFormat.Shape.STRING) long messageId,UUID conversationId,UUID requestId,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) Long senderUserId,String authorKind,String text,
                           Map<String,Object> attachment,String createdAt,String status) { }
    public record Event(@JsonFormat(shape=JsonFormat.Shape.STRING) long eventId,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) Long actorUserId,String type,JsonNode details,String createdAt) { }
    public record Read(@JsonFormat(shape=JsonFormat.Shape.STRING) long userId,@JsonFormat(shape=JsonFormat.Shape.STRING) long messageId,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) long eventId,@JsonFormat(shape=JsonFormat.Shape.STRING) long revision) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(UUID conversationId,List<Message> messages,boolean hasMore,List<Event> orderEvents,boolean eventsHaveMore,
                        List<Read> reads,Map<String,Object> conversation,String serverTime) { }
}
