package com.surprising.gateway.c2c.model;

import java.util.UUID;

/** 已绑定的资料与银行格式事实；该布尔值由服务端生成，不接受客户端提交。 */
public record C2cPaymentAccountInput(Details details, boolean bankDetailsComplete) {
    public record Details(String holderName, String accountNumber, String bankName, UUID qrAttachmentId) { }
}
