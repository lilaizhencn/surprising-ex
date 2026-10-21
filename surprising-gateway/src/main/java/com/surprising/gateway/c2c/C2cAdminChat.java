package com.surprising.gateway.c2c;

import java.util.*;
import com.surprising.gateway.c2c.model.C2cChatViews;
import com.surprising.gateway.c2c.model.C2cAttachmentContent;
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
    C2cChatViews.Page history(String auth,C2cAdminAccess.Request request,UUID order,long after,Long before,long event,int limit) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminPage(order,after,before,event,limit);
    }
    @Transactional
    C2cChatViews.Page latest(String auth,C2cAdminAccess.Request request,UUID order,int limit) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminLatest(order,limit);
    }
    @Transactional
    Map<String,Object> receipt(String auth,C2cAdminAccess.Request request,UUID order,UUID messageRequest) {
        var administrator=access.read(auth,true);orders.get(order,false);access.audit(administrator,request,new byte[0]);
        return messages.adminReceipt(administrator.userId(),order,messageRequest);
    }
    @Transactional
    C2cAttachmentContent attachment(String auth,C2cAdminAccess.Request request,UUID order,UUID attachment) {
        var administrator=access.read(auth,true);access.audit(administrator,request,new byte[0]);
        return attachments.adminDownload(attachment,order);
    }
    @Transactional
    C2cChatViews.Message reply(String auth,C2cAdminAccess.Request metadata,UUID order,UUID request,String text,byte[] body) {
        access.read(auth,true);var administrator=access.write(auth,metadata,body);
        var result=messages.support(administrator.userId(),order,request,text);
        access.audit(administrator,metadata,body);return result;
    }
}
