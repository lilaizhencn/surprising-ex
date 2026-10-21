package com.surprising.gateway.provider.model;

import java.util.UUID;

/** 已提交安全通知的一次投递租约；只在后台任务中使用，不作为用户接口返回。 */
public record SecurityEmailDelivery(
        UUID notificationId,
        UUID leaseId,
        String recipient,
        String subject,
        String body,
        int attempts) { }
