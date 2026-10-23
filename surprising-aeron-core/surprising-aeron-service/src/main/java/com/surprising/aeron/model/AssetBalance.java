package com.surprising.aeron.model;

import com.surprising.aeron.exception.CoreStateRejectedException;
import java.util.Locale;

/**
 * 一名用户某种资产的可用金额和冻结金额，用于冷状态导出、恢复与核对；冻结操作返回新记录并检查整数溢出。
 * @param asset 资产代码，去掉两端空格并统一为大写字母和数字
 * @param availableUnits 当前可以继续使用的金额，单位为该资产最小整数单位
 * @param lockedUnits 已冻结、暂时不能再次使用的金额，单位为该资产最小整数单位
 */
public record AssetBalance(String asset, long availableUnits, long lockedUnits) {

    /**
     * 规范资产代码，并拒绝负的可用金额或冻结金额，保证这份余额记录本身有效。
     */
    public AssetBalance {
        asset = normalizeAsset(asset);
        if (availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("balance units must not be negative");
        }
    }

    /**
     * 把可用和冻结相加，得到这份资产余额总额；不包含其他资产或未实现盈亏。
     */
    public long totalUnits() {
        return Math.addExact(availableUnits, lockedUnits);
    }

    /**
     * 把指定金额从可用余额转入冻结余额，用于订单等需要预占资金的业务，总额保持不变。
     * @param units 本次操作的正数金额，单位为资产最小整数单位
     */
    public AssetBalance reserve(long units) {
        requirePositive(units);
        if (availableUnits < units) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient");
        }
        return new AssetBalance(asset, Math.subtractExact(availableUnits, units), Math.addExact(lockedUnits, units));
    }

    /**
     * 去掉资产代码两端空格并转为大写，最终只接受 2 到 20 位英文字母或数字；非法代码直接拒绝。
     * @param value 需要规范化或检查的资产代码字符串
     */
    public static String normalizeAsset(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        // 去掉两端空格的代码，已经合规时可直接复用。
        String trimmed = value.trim();
        if (validAsset(trimmed)) return trimmed;
        // 按固定语言环境转成大写后的代码，还要再次校验字符。
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!validAsset(normalized)) {
            throw new IllegalArgumentException("invalid asset: " + value);
        }
        return normalized;
    }

    /**
     * 逐个字符检查资产代码是否已经符合长度及大写字母、数字要求，不修改传入字符串。
     * @param value 需要规范化或检查的资产代码字符串
     */
    private static boolean validAsset(String value) {
        // 资产代码的字符数量，限制在 2 到 20 位。
        int length = value.length();
        if (length < 2 || length > 20) {
            return false;
        }
        for (/* 本轮需要检查的字符下标，从 0 开始逐个向后检查。 */ int index = 0; index < length; index++) {
            // 本轮读到的字符，只允许大写英文字母或数字。
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 要求本次冻结、释放或收支金额大于 0，避免负金额反转业务方向。
     * @param units 本次操作的正数金额，单位为资产最小整数单位
     */
    private static void requirePositive(long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be positive");
        }
    }
}
