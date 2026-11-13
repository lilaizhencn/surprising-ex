package com.surprising.realtime.api.exception;

/** 已提交读视图未就绪、过期、落后或无效；保留原生原因，HTTP状态只在接入层选择。 */
public final class ReadViewUnavailableException extends RuntimeException {
    public ReadViewUnavailableException(String reason) { super(reason); }
    public ReadViewUnavailableException(String reason, Throwable cause) { super(reason, cause); }
}
