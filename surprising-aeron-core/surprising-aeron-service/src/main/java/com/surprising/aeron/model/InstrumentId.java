package com.surprising.aeron.model;

import com.surprising.product.api.utils.InstrumentIds;

/**
 * 永久币对编号文本的规范形式校验。编号必须是十进制正整数原文，不允许空值、前导零、非数字字符或超出 int 范围；校验通过后原样返回，供各业务记录构造器直接回写参数。
 */
public final class InstrumentId {

    private InstrumentId() {
    }

    /**
     * 校验永久正整数币对编号文本后原样返回，不重新生成或改写字符串。
     * @param value 需要校验的永久币对编号文本
     */
    public static String requireValid(String value) {
        InstrumentIds.parse(value);
        return value;
    }
}
