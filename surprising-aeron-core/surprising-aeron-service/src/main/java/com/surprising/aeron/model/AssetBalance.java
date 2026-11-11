package com.surprising.aeron.model;

import java.util.Locale;

/** 一名用户某种资产的可用金额和冻结金额，用于冷状态导出、恢复与核对。 */
public record AssetBalance(String asset, long availableUnits, long lockedUnits) {

    public AssetBalance {
        asset = normalizeAsset(asset);
        if (availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("balance units must not be negative");
        }
    }

    /** 去掉资产代码两端空格并转为大写，最终只接受 2 到 20 位英文字母或数字。 */
    public static String normalizeAsset(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String trimmed = value.trim();
        if (validAsset(trimmed)) return trimmed;
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!validAsset(normalized)) {
            throw new IllegalArgumentException("invalid asset: " + value);
        }
        return normalized;
    }

    private static boolean validAsset(String value) {
        int length = value.length();
        if (length < 2 || length > 20) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9')) {
                return false;
            }
        }
        return true;
    }

}
