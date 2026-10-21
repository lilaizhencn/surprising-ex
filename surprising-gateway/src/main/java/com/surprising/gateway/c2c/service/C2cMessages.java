package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;

import com.surprising.gateway.c2c.model.C2cAttachment;
import com.surprising.gateway.c2c.model.C2cChatViews;
import com.surprising.gateway.c2c.model.C2cConversation;
import com.surprising.gateway.c2c.model.C2cMessage;
import com.surprising.gateway.c2c.repository.C2cConversationRepository;
import com.surprising.gateway.c2c.repository.C2cMessageReadRepository;
import com.surprising.gateway.c2c.repository.C2cMessageRepository;
import com.surprising.gateway.c2c.repository.C2cOrderEventRepository;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合发送权限、原请求去重和单调已读水位；消息与会话更新时间共同提交，分页使用原生游标。 */
@Service
public class C2cMessages {
    private final C2cMessageRepository messages;
    private final C2cMessageReadRepository reads;
    private final C2cConversationRepository rows;
    private final C2cOrderEventRepository events;
    private final GatewayUserRepository users;
    private final C2cConversations conversations;
    private final C2cAttachments attachments;
    public C2cMessages(C2cMessageRepository messages,C2cMessageReadRepository reads,C2cConversationRepository rows,
                        C2cOrderEventRepository events,GatewayUserRepository users,C2cConversations conversations,C2cAttachments attachments) {
        this.messages=messages;this.reads=reads;this.rows=rows;this.events=events;this.users=users;
        this.conversations=conversations;this.attachments=attachments;
    }
    @Transactional
    public C2cChatViews.Message send(long user,UUID conversation,UUID request,String text,UUID attachment) {
        return append(user,conversation,request,text,attachment,false);
    }
    @Transactional
    public C2cChatViews.Message support(long administrator,UUID order,UUID request,String text) {
        return append(administrator,conversations.forOrder(order).id(),request,text,null,true);
    }
    private C2cChatViews.Message append(long user,UUID id,UUID request,String text,UUID attachment,boolean support) {
        if(!users.lockForNoKeyUpdate(user)) throw new IllegalStateException("账户不可用");
        var conversation=support?conversations.byId(id,true):conversations.get(user,id,true);
        if(support && (conversation.orderId()==null || user==conversation.low() || user==conversation.high()))
            throw C2cProblem.forbidden("不能以客服身份参与本人的交易");
        String kind=support?"SUPPORT":"USER";
        var previous=messages.byRequest(id,request);
        if(previous.isPresent()) {
            var original=previous.get();
            if(!Objects.equals(original.sender(),user) || !kind.equals(original.kind()) || !text.equals(original.text())
                    || !Objects.equals(attachment,original.attachment())) throw new IllegalStateException("消息请求号已用于其他内容");
            return view(original);
        }
        if(!support) conversations.requireSend(user,conversation);
        if(attachment!=null) attachments.message(user,attachment,conversation);
        var activity=messages.activity(user);
        if(activity.minute()>=60 || activity.day()>=5000) throw new C2cProblem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "C2C_MESSAGE_LIMIT","发送过于频繁，请稍后再试");
        messages.insert(id,user,request,text,attachment,kind);rows.touch(id);
        return view(messages.byRequest(id,request).orElseThrow());
    }
    public Map<String,Object> receipt(long user,UUID id,UUID request) {
        conversations.get(user,id,false);var message=messages.byRequest(id,request);
        if(message.isEmpty() || !Objects.equals(message.get().sender(),user)) return Map.of("requestId",request.toString(),"status","UNKNOWN");
        return Map.of("requestId",request.toString(),"status","SENT","message",view(message.get()));
    }
    public C2cChatViews.Page page(long user,UUID id,long after,Long before,long afterEvent,int limit) {
        return page(conversations.get(user,id,false),user,after,before,afterEvent,limit,false);
    }
    public C2cChatViews.Page latest(long user,UUID id,int limit) {
        return page(conversations.get(user,id,false),user,0,null,0,limit,true);
    }
    public C2cChatViews.Page adminPage(UUID order,long after,Long before,long afterEvent,int limit) {
        return adminPage(order,after,before,afterEvent,limit,false);
    }
    public C2cChatViews.Page adminLatest(UUID order,int limit) {return adminPage(order,0,null,0,limit,true);}
    private C2cChatViews.Page adminPage(UUID order,long after,Long before,long afterEvent,int limit,boolean latest) {
        var id=rows.forOrder(order);
        if(id.isEmpty()) return new C2cChatViews.Page(null,List.of(),false,List.of(),false,null,null,null);
        return page(conversations.byId(id.get(),false),0,after,before,afterEvent,limit,latest);
    }
    public Map<String,Object> adminReceipt(long administrator,UUID order,UUID request) {
        var id=rows.forOrder(order);
        var message=id.flatMap(value -> messages.byRequest(value,request));
        if(message.isEmpty() || !"SUPPORT".equals(message.get().kind()) || !Objects.equals(message.get().sender(),administrator)) return Map.of("status","UNKNOWN");
        return Map.of("status","SENT","message",view(message.get()));
    }
    private C2cChatViews.Page page(C2cConversation conversation,long user,long after,Long before,long afterEvent,int limit,boolean latest) {
        if(after>0) requireMessage(conversation.id(),after);
        if(before!=null) requireMessage(conversation.id(),before);
        if(afterEvent>0) requireEvent(conversation.orderId(),afterEvent);
        boolean older=latest || before!=null;long boundary=latest?Long.MAX_VALUE:before==null?after:before;
        var rows=messages.page(conversation.id(),boundary,older,latest,limit+1);
        var visible=new ArrayList<>(rows.stream().limit(limit).toList());
        if(older) Collections.reverse(visible);
        var files=attachments.readyByIds(visible.stream().map(C2cMessage::attachment).filter(Objects::nonNull).toList());
        var messageViews=visible.stream().map(row -> view(row,row.attachment()==null?null:files.get(row.attachment()))).toList();
        var eventRows=events.after(conversation.orderId(),afterEvent,limit+1);
        var eventViews=eventRows.stream().limit(limit).map(row -> new C2cChatViews.Event(row.id(),row.actorUserId(),row.type(),row.details(),row.createdAt().toString())).toList();
        return new C2cChatViews.Page(conversation.id(),messageViews,rows.size()>limit,eventViews,eventRows.size()>limit,
                reads(conversation),user>0?conversations.view(user,conversation):null,Instant.now().toString());
    }
    @Transactional
    public Map<String,Object> markRead(long user,UUID id,long message,long event) {
        var conversation=conversations.get(user,id,true);
        if(message>0) requireMessage(id,message);
        if(event>0) requireEvent(conversation.orderId(),event);
        reads.advance(id,user,message,event);return Map.of("reads",reads(conversation),"serverTime",Instant.now().toString());
    }
    private List<C2cChatViews.Read> reads(C2cConversation conversation) {
        return reads.forConversation(conversation.id()).stream().map(row -> new C2cChatViews.Read(row.userId(),row.messageId(),row.eventId(),row.revision())).toList();
    }
    private void requireMessage(UUID conversation,long id) {
        if(!messages.exists(conversation,id)) throw new IllegalArgumentException("消息位置不属于此会话");
    }
    private void requireEvent(UUID order,long id) {
        if(!events.exists(order,id)) throw new IllegalArgumentException("事件位置不属于此订单");
    }
    private C2cChatViews.Message view(C2cMessage message) {
        return view(message,message.attachment()==null?null:attachments.ready(message.attachment()));
    }
    private C2cChatViews.Message view(C2cMessage message,C2cAttachment file) {
        if(message.attachment()!=null && file==null) throw new IllegalArgumentException("文件尚不可用");
        return new C2cChatViews.Message(message.id(),message.conversation(),message.request(),message.sender(),message.kind(),message.text(),
                file==null?null:attachments.view(file),message.createdAt().toString(),"SENT");
    }
}
