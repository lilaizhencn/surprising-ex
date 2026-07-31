package com.surprising.instrument.api;

public final class InstrumentApiPaths {

    public static final String BASE_PATH = "/api/v1/instruments";
    /** 仅供服务间初始化和快照修复使用的内部读取入口。 */
    public static final String INTERNAL_BASE_PATH = "/internal/v1/instruments";
    public static final String ADMIN_BASE_PATH = "/api/v1/instruments/admin";

    private InstrumentApiPaths() {
    }
}
