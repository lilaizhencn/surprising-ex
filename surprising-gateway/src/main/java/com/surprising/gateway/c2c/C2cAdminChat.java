package com.surprising.gateway.c2c;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只能由订单进入证据；敏感读取及客服回复与必需审计一起提交。 */
@Service
public class C2cAdminChat {
    private final C2cAdminAccess access;
    private final C2cOrdersRepository orders;
    private final C2cMessages messages;
    private final C2cAttachments attachments;
    public C2cAdminChat(C2cAdminAccess access,C2cOrdersRepository orders,C2cMessages messages,C2cAttachments attachments) {
        this.access=access;this.orders=orders;this.messages=messages;this.attachments=attachments;
    }
    @Transactional
    Map<String,Object> history(String auth,C2cAdminAccess.Request request,UUID order,long after,Long before,long event,int limit) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminPage(order,after,before,event,limit);
    }
    @Transactional
    Map<String,Object> latest(String auth,C2cAdminAccess.Request request,UUID order,int limit) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminLatest(order,limit);
    }
    @Transactional
    Map<String,Object> receipt(String auth,C2cAdminAccess.Request request,UUID order,UUID messageRequest) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminReceipt(administrator.userId(),order,messageRequest);
    }
    @Transactional
    C2cAttachments.Content attachment(String auth,C2cAdminAccess.Request request,UUID order,UUID attachment) {
        var administrator=access.read(auth,true);access.audit(administrator,request,new byte[0]);
        return attachments.adminDownload(attachment,order);
    }
    @Transactional
    Map<String,Object> reply(String auth,C2cAdminAccess.Request metadata,UUID order,C2cChatController.Send request,byte[] body) {
        access.read(auth,true);var administrator=access.write(auth,metadata,body);
        if(request.attachmentId()!=null) throw new IllegalArgumentException("客服回复暂只支持文字");
        var result=messages.support(administrator.userId(),order,request.requestId(),request.text());
        access.audit(administrator,metadata,body);return result;
    }
}
