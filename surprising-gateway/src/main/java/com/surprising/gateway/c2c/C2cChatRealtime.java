package com.surprising.gateway.c2c;

import com.surprising.websocket.provider.service.ClientConnection;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 聊天直接使用行情 /ws/v1 的已认证连接、心跳和发送队列；这里只保存聊天订阅和补拉游标。 */
@Component
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cChatRealtime {
    public record Command(String op,String id,String conversationId,String after,String afterEvent) { }
    private static final class Subscription {
        final ClientConnection connection;
        final ReentrantLock lock=new ReentrantLock();
        UUID conversation;
        long after,event;
        long rateWindow=System.nanoTime();int commands;
        String signal,reads;
        Subscription(ClientConnection connection) {this.connection=connection;}
    }
    private final Map<String,Subscription> subscriptions=new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final C2cAccess access;
    private final C2cConversations conversations;
    private final C2cMessages messages;
    private final int capacity;
    public C2cChatRealtime(ObjectMapper json,C2cAccess access,C2cConversations conversations,C2cMessages messages,
            @Value("${surprising.c2c.chat.max-connections:256}") int capacity) {
        if(capacity<1 || capacity>10000) throw new IllegalArgumentException("C2C 连接容量无效");
        this.json=json;this.access=access;this.conversations=conversations;this.messages=messages;this.capacity=capacity;
    }

    public void handle(ClientConnection connection,Command command) {
        long user=requireUser(connection);
        if(command.id()!=null && command.id().length()>80) throw new IllegalArgumentException("聊天请求号无效");
        Subscription subscription;
        synchronized(subscriptions) {
            subscription=subscriptions.get(connection.id());
            if(subscription==null) {
                if(subscriptions.size()>=capacity) throw C2cProblem.unavailable("聊天连接已满，请稍后重试");
                subscription=new Subscription(connection);subscriptions.put(connection.id(),subscription);
            }
        }
        subscription.lock.lock();
        try {
            if(System.nanoTime()-subscription.rateWindow>60_000_000_000L) {subscription.commands=0;subscription.rateWindow=System.nanoTime();}
            if(++subscription.commands>120) throw new IllegalArgumentException("聊天请求过于频繁");
            switch(command.op()) {
                case "c2c.watch" -> {
                    UUID id=UUID.fromString(command.conversationId());conversations.get(user,id,false);
                    long after=C2cMessages.cursor(command.after()==null?"0":command.after());
                    long event=C2cMessages.cursor(command.afterEvent()==null?"0":command.afterEvent());
                    var page=messages.page(user,id,after,null,event,20);
                    subscription.conversation=id;subscription.after=after;subscription.event=event;subscription.reads=null;
                    deliver(subscription,page,true);
                }
                case "c2c.unwatch" -> {subscription.conversation=null;send(subscription,Map.of("op","c2c.unwatched","channel","c2c.messages"));}
                case "c2c.inbox" -> {subscription.signal=null;send(subscription,Map.of("op","c2c.subscribed","channel","c2c.inbox"));}
                case "c2c.stop" -> closed(connection.id());
                default -> throw new IllegalArgumentException("聊天命令无效");
            }
        } finally {subscription.lock.unlock();}
    }

    @Scheduled(fixedDelay=1000,scheduler="c2cChatScheduler")
    public void poll() {
        for(var entry:subscriptions.entrySet()) {
            var subscription=entry.getValue();if(!subscription.lock.tryLock())continue;
            try {
                if(!subscription.connection.isOpen()) {closed(entry.getKey());continue;}
                long user=requireUser(subscription.connection);
                String signal=conversations.signal(user);
                if(!signal.equals(subscription.signal)) {
                    if(send(subscription,Map.of("op","c2c.changed","channel","c2c.inbox","eventTime",Instant.now().toString()))) subscription.signal=signal;
                }
                if(subscription.conversation!=null) deliver(subscription,
                        messages.page(user,subscription.conversation,subscription.after,null,subscription.event,20),false);
            } catch(Exception failure) {
                closed(entry.getKey());
                // 身份失效由 requireUser 关闭整个连接；聊天存储暂不可用只停止聊天，保留行情。
                if(subscription.connection.isOpen()) send(subscription,Map.of("op","c2c.error","channel","c2c.messages",
                        "message","聊天暂不可用，请重新订阅"));
            } finally {subscription.lock.unlock();}
        }
    }
    private long requireUser(ClientConnection connection) {
        try {
            String token=connection.accessToken();
            if(token==null) throw new IllegalArgumentException();
            var user=access.user("Bearer "+token);
            if(!Objects.equals(connection.authenticatedUserId(),user.userId())) throw new IllegalArgumentException();
            return user.userId();
        } catch(RuntimeException failure) {
            connection.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
            throw C2cProblem.forbidden("登录状态已失效，请重新登录");
        }
    }
    private void deliver(Subscription subscription,Map<String,Object> page,boolean initial) {
        var items=(List<Map<String,Object>>)page.get("messages");var events=(List<Map<String,Object>>)page.get("orderEvents");
        String reads=json.writeValueAsString(page.get("reads"));
        if(initial || !items.isEmpty() || !events.isEmpty() || !reads.equals(subscription.reads)) {
            if(send(subscription,Map.of("op","c2c.messages","channel","c2c.messages","data",page))) {
                if(!items.isEmpty())subscription.after=C2cMessages.cursor((String)items.getLast().get("messageId"));
                if(!events.isEmpty())subscription.event=C2cMessages.cursor((String)events.getLast().get("eventId"));
                subscription.reads=reads;
            }
        }
    }
    private boolean send(Subscription subscription,Map<String,Object> message) {
        // 行情已有积压时暂缓聊天；不推进游标，下轮从原位置续发，聊天不会填满行情队列。
        if(subscription.connection.queuedMessages()>=Math.min(4,subscription.connection.queueCapacity())) return false;
        return subscription.connection.send(json.writeValueAsString(message));
    }
    public void closed(String id) {subscriptions.remove(id);}
    @PreDestroy public void stop() {subscriptions.clear();}
}
