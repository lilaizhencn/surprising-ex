package com.surprising.derivatives.lifecycle.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 产品线完整生命周期规则的数据库版本，安装完成后作为调度刷新事件。 */
public record LifecycleSettingsSnapshot(long version, LifecycleBusinessSettings settings,
                                        String updatedBy, String reason, Instant updatedAt) {

    public Map<String, Object> responseEnvelope(Map<String, Object> sections) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "DATABASE");
        response.put("version", version);
        response.put("updatedBy", updatedBy);
        response.put("reason", reason);
        response.putAll(sections);
        return Collections.unmodifiableMap(response);
    }

    public static <T> T coalesce(T update, T previous) {
        return update == null ? previous : update;
    }
}
