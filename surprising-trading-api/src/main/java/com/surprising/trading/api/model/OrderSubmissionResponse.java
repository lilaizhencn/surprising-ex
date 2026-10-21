package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** 已提交交易处理结果；RESULT_UNKNOWN 只表示尚无投影，不能证明原请求未执行。 */
public record OrderSubmissionResponse(long userId, ProductLine productLine, String clientOrderId,
                                      String instrumentId, UUID commandId, String outcome, String code) { }
