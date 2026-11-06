package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.notification.model.PushOutboxItem;

/** 系统推送通道底层发送协议接口 */
public interface PushSender {
    /**
     * 发送系统通知至指定设备
     * @param item 出件箱待发送对象
     * @throws Exception 发送异常，触发重试
     */
    void send(PushOutboxItem item) throws Exception;
}
