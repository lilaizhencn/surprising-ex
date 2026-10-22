package com.surprising.gateway.c2c.utils;

import com.surprising.gateway.c2c.model.C2cPaymentAccountInput;

/** 无状态资料格式工具；Controller绑定输入，审核时也可重核解密后的存量事实。 */
public final class C2cPaymentFormats {
    private C2cPaymentFormats() { }

    public static C2cPaymentAccountInput input(C2cPaymentAccountInput.Details details) {
        boolean complete = details != null && details.bankName() != null && !details.bankName().isBlank()
                && details.accountNumber() != null && details.accountNumber().trim().matches("[0-9]{8,40}");
        return new C2cPaymentAccountInput(details, complete);
    }
}
