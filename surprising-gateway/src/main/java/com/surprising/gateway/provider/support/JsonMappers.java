package com.surprising.gateway.provider.support;

import tools.jackson.databind.ObjectMapper;

/** 共享默认 ObjectMapper 兜底实例，避免各服务重复创建静态 new ObjectMapper()。 */
public final class JsonMappers {
    public static final ObjectMapper DEFAULT = new ObjectMapper();

    private JsonMappers() {
    }

    public static ObjectMapper orDefault(ObjectMapper mapper) {
        return mapper == null ? DEFAULT : mapper;
    }
}
