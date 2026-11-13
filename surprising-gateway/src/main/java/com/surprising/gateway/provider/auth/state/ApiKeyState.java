package com.surprising.gateway.provider.auth.state;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Valkey 中的 API Key（auth:apikey:{apiKey}）；密钥只存加密后的密文，永不过期。 */
public record ApiKeyState(String apiKey, UUID apiKeyId, long userId, String permissions, String ipAllowlist,
                          String secretCiphertext) {

    Map<String, String> toFields() {
        var fields = new HashMap<String, String>();
        fields.put("id", apiKeyId.toString());
        fields.put("uid", Long.toString(userId));
        fields.put("permissions", permissions);
        if (ipAllowlist != null) fields.put("ipAllowlist", ipAllowlist);
        fields.put("secret", secretCiphertext);
        return fields;
    }

    static ApiKeyState fromFields(String apiKey, Map<String, String> fields) {
        return new ApiKeyState(apiKey, UUID.fromString(fields.get("id")), Long.parseLong(fields.get("uid")),
                fields.get("permissions"), fields.get("ipAllowlist"), fields.get("secret"));
    }
}
