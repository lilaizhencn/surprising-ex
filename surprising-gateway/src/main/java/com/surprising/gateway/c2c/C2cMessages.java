package com.surprising.gateway.c2c;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 迁用 social 的发送确认、持久消息去重、游标补拉与已读水位，适配交易库和订单会话。 */
@Service
public class C2cMessages {
    record Message(long id,UUID conversation,Long sender,UUID request,String text,UUID attachment,String kind,Instant createdAt) { }
    private final JdbcTemplate jdbc;
    private final C2cConversations conversations;
    private final C2cAttachments attachments;
    private final ObjectMapper json;
    public C2cMessages(JdbcTemplate jdbc,C2cConversations conversations,C2cAttachments attachments,ObjectMapper json) {
        this.jdbc=jdbc;this.conversations=conversations;this.attachments=attachments;this.json=json;
    }

    @Transactional
    Map<String,Object> send(long user,UUID conversation,UUID request,String text,UUID attachment) {
        return append(user,conversation,request,text,attachment,false);
    }
    @Transactional
    Map<String,Object> support(long administrator,UUID order,UUID request,String text) {
        return append(administrator,conversations.forOrder(order).id(),request,text,null,true);
    }
    private Map<String,Object> append(long user,UUID id,UUID request,String raw,UUID attachment,boolean support) {
        String text=normalize(raw);
        if(request==null || (text.isBlank() && attachment==null)) throw new IllegalArgumentException("请输入消息或选择附件");
        jdbc.queryForObject("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",Long.class,user);
        var conversation=support?conversations.byId(id,true):conversations.get(user,id,true);
        if(support && (conversation.orderId()==null || user==conversation.low() || user==conversation.high()))
            throw C2cProblem.forbidden("不能以客服身份参与本人的交易");
        String kind=support?"SUPPORT":"USER";
        var previous=byRequest(id,request);
        if(previous!=null) {
            if(!Objects.equals(previous.sender(),user) || !kind.equals(previous.kind()) || !text.equals(previous.text())
                    || !Objects.equals(attachment,previous.attachment())) throw new IllegalStateException("消息请求号已用于其他内容");
            return view(previous);
        }
        if(!support) conversations.requireSend(user,conversation);
        if(attachment!=null) attachments.message(user,attachment,conversation);
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE created_at>now()-interval '1 minute')>=60
                    OR count(*)>=5000 FROM c2c_messages WHERE sender_user_id=? AND created_at>now()-interval '24 hours'
                """,Boolean.class,user))) throw new C2cProblem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "C2C_MESSAGE_LIMIT","发送过于频繁，请稍后再试");
        jdbc.update("""
                INSERT INTO c2c_messages(conversation_id,sender_user_id,request_id,text,attachment_id,author_kind)
                VALUES(?,?,?,?,?,?)
                """,id,user,request,text,attachment,kind);
        jdbc.update("UPDATE c2c_conversations SET updated_at=now() WHERE conversation_id=?",id);
        return view(byRequest(id,request));
    }

    Map<String,Object> receipt(long user,UUID id,UUID request) {
        conversations.get(user,id,false);var value=byRequest(id,request);
        if(value==null || !Objects.equals(value.sender(),user)) return Map.of("requestId",request.toString(),"status","UNKNOWN");
        return Map.of("requestId",request.toString(),"status","SENT","message",view(value));
    }

    Map<String,Object> page(long user,UUID id,long after,Long before,long afterEvent,int limit) {
        return page(conversations.get(user,id,false),user,after,before,afterEvent,limit);
    }
    Map<String,Object> latest(long user,UUID id,int limit) {
        return page(conversations.get(user,id,false),user,0,null,0,limit,true);
    }
    Map<String,Object> adminPage(UUID order,long after,Long before,long afterEvent,int limit) {
        return adminPage(order,after,before,afterEvent,limit,false);
    }
    Map<String,Object> adminLatest(UUID order,int limit) {return adminPage(order,0,null,0,limit,true);}
    private Map<String,Object> adminPage(UUID order,long after,Long before,long afterEvent,int limit,boolean latest) {
        var ids=jdbc.query("SELECT conversation_id FROM c2c_conversations WHERE order_id=?",(r,n)->r.getObject(1,UUID.class),order);
        if(ids.isEmpty()) return Map.of("messages",List.of(),"orderEvents",List.of(),"hasMore",false,"eventsHaveMore",false);
        return page(conversations.byId(ids.getFirst(),false),0,after,before,afterEvent,limit,latest);
    }
    Map<String,Object> adminReceipt(long administrator,UUID order,UUID request) {
        var ids=jdbc.query("SELECT conversation_id FROM c2c_conversations WHERE order_id=?",(r,n)->r.getObject(1,UUID.class),order);
        Message message=ids.isEmpty()?null:byRequest(ids.getFirst(),request);
        if(message==null || !"SUPPORT".equals(message.kind()) || !Objects.equals(message.sender(),administrator))
            return Map.of("status","UNKNOWN");
        return Map.of("status","SENT","message",view(message));
    }
    private Map<String,Object> page(C2cConversations.Conversation conversation,long user,long after,Long before,long afterEvent,int limit) {
        return page(conversation,user,after,before,afterEvent,limit,false);
    }
    private Map<String,Object> page(C2cConversations.Conversation conversation,long user,long after,Long before,long afterEvent,int limit,boolean latest) {
        if(after<0 || afterEvent<0 || (before!=null && (before<1 || after!=0)) || limit<1 || limit>50)
            throw new IllegalArgumentException("消息查询条件无效");
        if(after>0) requireMessage(conversation.id(),after);
        if(before!=null) requireMessage(conversation.id(),before);
        if(afterEvent>0) requireEvent(conversation.orderId(),afterEvent);
        boolean older=latest || before!=null;
        long boundary=latest?Long.MAX_VALUE:before==null?after:before;
        var rows=jdbc.query("SELECT * FROM c2c_messages WHERE conversation_id=? AND message_id "+(latest?"<=":older?"<":">")
                +" ? ORDER BY message_id "+(older?"DESC":"ASC")+" LIMIT ?",this::read,conversation.id(),boundary,limit+1);
        var messages=new ArrayList<>(rows.stream().limit(limit).toList());
        if(older) Collections.reverse(messages);
        var events=events(conversation.orderId(),afterEvent,limit+1);
        var result=new LinkedHashMap<String,Object>();
        result.put("conversationId",conversation.id().toString());result.put("messages",messages.stream().map(this::view).toList());
        result.put("hasMore",rows.size()>limit);result.put("orderEvents",events.stream().limit(limit).toList());
        result.put("eventsHaveMore",events.size()>limit);result.put("reads",reads(conversation));
        if(user>0) result.put("conversation",conversations.view(user,conversation));
        result.put("serverTime",Instant.now().toString());return result;
    }

    @Transactional
    Map<String,Object> markRead(long user,UUID id,long message,long event) {
        var conversation=conversations.get(user,id,true);
        if(message<0 || event<0) throw new IllegalArgumentException("已读位置无效");
        if(message>0) requireMessage(id,message);
        if(event>0) requireEvent(conversation.orderId(),event);
        jdbc.update("""
                INSERT INTO c2c_message_reads(conversation_id,user_id,last_message_id,last_event_id,revision)
                VALUES(?,?,?,?,1) ON CONFLICT(conversation_id,user_id) DO UPDATE SET
                    last_message_id=GREATEST(c2c_message_reads.last_message_id,EXCLUDED.last_message_id),
                    last_event_id=GREATEST(c2c_message_reads.last_event_id,EXCLUDED.last_event_id),revision=c2c_message_reads.revision+1
                    WHERE c2c_message_reads.last_message_id<EXCLUDED.last_message_id OR c2c_message_reads.last_event_id<EXCLUDED.last_event_id
                """,id,user,message,event);
        return Map.of("reads",reads(conversation),"serverTime",Instant.now().toString());
    }
    List<Map<String,Object>> reads(C2cConversations.Conversation conversation) {
        return jdbc.query("SELECT * FROM c2c_message_reads WHERE conversation_id=? ORDER BY user_id",(r,n)->Map.<String,Object>of(
                "userId",Long.toString(r.getLong("user_id")),"messageId",Long.toString(r.getLong("last_message_id")),
                "eventId",Long.toString(r.getLong("last_event_id")),"revision",Long.toString(r.getLong("revision"))),conversation.id());
    }
    private List<Map<String,Object>> events(UUID order,long after,int limit) {
        return jdbc.query("SELECT * FROM c2c_order_events WHERE order_id=? AND event_id>? ORDER BY event_id LIMIT ?",(r,n)->{
            var event=new LinkedHashMap<String,Object>();event.put("eventId",Long.toString(r.getLong("event_id")));
            event.put("actorUserId",r.getObject("actor_user_id")==null?null:Long.toString(r.getLong("actor_user_id")));
            event.put("type",r.getString("event_type"));event.put("details",json.readValue(r.getString("details"),Map.class));
            event.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return event;
        },order,after,limit);
    }
    private void requireMessage(UUID conversation,long id) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_messages WHERE conversation_id=? AND message_id=?)",
                Boolean.class,conversation,id))) throw new IllegalArgumentException("消息位置不属于此会话");
    }
    private void requireEvent(UUID order,long event) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_order_events WHERE order_id=? AND event_id=?)",
                Boolean.class,order,event))) throw new IllegalArgumentException("事件位置不属于此订单");
    }
    private Message byRequest(UUID id,UUID request) {
        return jdbc.query("SELECT * FROM c2c_messages WHERE conversation_id=? AND request_id=?",this::read,id,request).stream().findFirst().orElse(null);
    }
    private Message read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Message(r.getLong("message_id"),r.getObject("conversation_id",UUID.class),(Long)r.getObject("sender_user_id"),
                r.getObject("request_id",UUID.class),r.getString("text"),r.getObject("attachment_id",UUID.class),r.getString("author_kind"),
                r.getTimestamp("created_at").toInstant());
    }
    private Map<String,Object> view(Message message) {
        var value=new LinkedHashMap<String,Object>();value.put("messageId",Long.toString(message.id()));
        value.put("conversationId",message.conversation().toString());value.put("requestId",message.request().toString());
        value.put("senderUserId",message.sender()==null?null:Long.toString(message.sender()));value.put("authorKind",message.kind());
        value.put("text",message.text());value.put("attachment",message.attachment()==null?null:attachments.view(attachments.ready(message.attachment())));
        value.put("createdAt",message.createdAt().toString());value.put("status","SENT");return value;
    }
    static long cursor(String value) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("消息游标无效");
        try {return Long.parseLong(value);} catch(NumberFormatException failure) {throw new IllegalArgumentException("消息游标超出范围");}
    }
    private static String normalize(String raw) {
        String text=raw==null?"":raw.replace("\r\n","\n").strip();
        if(text.length()>4000 || text.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException("消息内容无效或超过 4000 字");
        return text;
    }
}
