package com.surprising.aeron.service.query;

/** 查询扫描范围或物化规模超过允许上限时使用的内部异常，用来与记录不存在等正常空结果区分。 */
public final class QueryTooLargeException extends IllegalStateException {
}
