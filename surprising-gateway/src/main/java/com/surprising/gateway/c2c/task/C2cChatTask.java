package com.surprising.gateway.c2c.task;

import com.surprising.gateway.c2c.C2cChatRealtime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只触发聊天补拉；订阅/背压/发送成功后的游标由原传输适配器持有。 */
@Component
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cChatTask {
    private final C2cChatRealtime realtime;
    public C2cChatTask(C2cChatRealtime realtime) {this.realtime=realtime;}
    @Scheduled(fixedDelay=1000,scheduler="c2cChatScheduler")
    public void poll() {realtime.poll();}
}
