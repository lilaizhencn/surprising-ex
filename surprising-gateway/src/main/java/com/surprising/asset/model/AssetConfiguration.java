package com.surprising.asset.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/** 币种是账户资产；Network 只描述同一币种的一个充提入口。 */
public final class AssetConfiguration {
    private AssetConfiguration() {}

    private static int defaultDisplayDecimals(long scaleUnits) {
        if (scaleUnits <= 1L) return 0;
        int decimals = (int) Math.round(Math.log10(scaleUnits));
        return Math.min(8, Math.max(2, decimals));
    }

    public record Asset(
            int assetId,
            String asset,
            String displayName,
            String aliasName,
            String assetType,
            String logoUrl,
            long scaleUnits,
            int displayDecimals,
            int sortOrder,
            long collateralHaircutPpm,
            String officialWebsite,
            String explorerUrl,
            boolean listed,
            boolean visible,
            boolean tradingEnabled,
            boolean depositEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            boolean internalTransferEnabled,
            boolean c2cEnabled,
            boolean collateralEnabled,
            long revision,
            Instant updatedAt
    ) {
        public Asset(int assetId, String asset, String displayName, String logoUrl, long scaleUnits,
                     boolean listed, boolean tradingEnabled, long revision, Instant updatedAt) {
            this(assetId, asset, displayName, "", "CRYPTO", logoUrl, scaleUnits,
                    defaultDisplayDecimals(scaleUnits), 100, 1_000_000L, "", "",
                    listed, listed, tradingEnabled, listed, listed, listed, false, false, false,
                    revision, updatedAt);
        }

        public Asset(int assetId, String asset, String displayName, String logoUrl, long scaleUnits,
                     boolean listed, boolean tradingEnabled, boolean depositEnabled,
                     boolean withdrawalEnabled, boolean transferEnabled, long revision, Instant updatedAt) {
            this(assetId, asset, displayName, "", "CRYPTO", logoUrl, scaleUnits,
                    defaultDisplayDecimals(scaleUnits), 100, 1_000_000L, "", "",
                    listed, listed, tradingEnabled, depositEnabled, withdrawalEnabled, transferEnabled,
                    false, false, false, revision, updatedAt);
        }
    }

    public record AssetRequest(
            Integer assetId,
            String asset,
            String displayName,
            String aliasName,
            String assetType,
            String logoUrl,
            long scaleUnits,
            Integer displayDecimals,
            Integer sortOrder,
            Long collateralHaircutPpm,
            String officialWebsite,
            String explorerUrl,
            boolean listed,
            Boolean visible,
            boolean tradingEnabled,
            boolean depositEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            Boolean internalTransferEnabled,
            Boolean c2cEnabled,
            Boolean collateralEnabled,
            long revision,
            String reason
    ) {
        public AssetRequest {
            aliasName = aliasName == null ? "" : aliasName.trim();
            assetType = (assetType == null || assetType.isBlank())
                    ? "CRYPTO"
                    : assetType.trim().toUpperCase(Locale.ROOT);
            displayDecimals = displayDecimals == null ? defaultDisplayDecimals(scaleUnits) : displayDecimals;
            sortOrder = sortOrder == null ? 100 : sortOrder;
            collateralHaircutPpm = collateralHaircutPpm == null ? 1_000_000L : collateralHaircutPpm;
            officialWebsite = officialWebsite == null ? "" : officialWebsite.trim();
            explorerUrl = explorerUrl == null ? "" : explorerUrl.trim();
            visible = visible == null ? Boolean.TRUE : visible;
            internalTransferEnabled = Boolean.TRUE.equals(internalTransferEnabled);
            c2cEnabled = Boolean.TRUE.equals(c2cEnabled);
            collateralEnabled = Boolean.TRUE.equals(collateralEnabled);
        }

        public AssetRequest(Integer assetId, String asset, String displayName, String logoUrl,
                            long scaleUnits, boolean listed, boolean tradingEnabled, long revision,
                            String reason) {
            this(assetId, asset, displayName, "", "CRYPTO", logoUrl, scaleUnits,
                    defaultDisplayDecimals(scaleUnits), 100, 1_000_000L, "", "",
                    listed, listed, tradingEnabled, listed, listed, listed,
                    false, false, false, revision, reason);
        }

        public AssetRequest(Integer assetId, String asset, String displayName, String logoUrl,
                            long scaleUnits, boolean listed, boolean tradingEnabled,
                            boolean depositEnabled, boolean withdrawalEnabled, boolean transferEnabled,
                            long revision, String reason) {
            this(assetId, asset, displayName, "", "CRYPTO", logoUrl, scaleUnits,
                    defaultDisplayDecimals(scaleUnits), 100, 1_000_000L, "", "",
                    listed, true, tradingEnabled, depositEnabled, withdrawalEnabled, transferEnabled,
                    false, false, false, revision, reason);
        }
    }

    public record Network(
            int networkId,
            int assetId,
            String networkCode,
            String displayName,
            String tokenStandard,
            String contractAddress,
            boolean nativeAsset,
            int chainDecimals,
            boolean enabled,
            boolean isDefault,
            boolean depositEnabled,
            boolean withdrawalEnabled,
            boolean memoRequired,
            boolean internalWithdrawEnabled,
            boolean autoWithdrawEnabled,
            boolean maintenanceMode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minDeposit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyMaxWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal withdrawalFee,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal autoAuditMaxAmount,
            int withdrawPrecision,
            int confirmations,
            int unlockConfirmations,
            int estimatedArrivalMinutes,
            String addressRegex,
            String memoRegex,
            String explorerTxUrlTemplate,
            String specialTips,
            long revision,
            Instant updatedAt
    ) {
        public Network(int networkId, int assetId, String networkCode, String displayName,
                       String contractAddress, boolean nativeAsset, int chainDecimals,
                       boolean depositEnabled, boolean withdrawalEnabled,
                       BigDecimal minDeposit, BigDecimal minWithdrawal, BigDecimal withdrawalFee,
                       int confirmations, long revision, Instant updatedAt) {
            this(networkId, assetId, networkCode, displayName, nativeAsset ? "NATIVE" : "TOKEN",
                    contractAddress, nativeAsset, chainDecimals, true, false,
                    depositEnabled, withdrawalEnabled, false, true, true, false,
                    minDeposit, minWithdrawal, null, null, withdrawalFee, null,
                    Math.max(chainDecimals, 0), confirmations, confirmations, 3,
                    "", "", "", "", revision, updatedAt);
        }
    }

    public record NetworkRequest(
            Integer networkId,
            String networkCode,
            String displayName,
            String tokenStandard,
            String contractAddress,
            boolean nativeAsset,
            int chainDecimals,
            Boolean enabled,
            Boolean isDefault,
            boolean depositEnabled,
            boolean withdrawalEnabled,
            Boolean memoRequired,
            Boolean internalWithdrawEnabled,
            Boolean autoWithdrawEnabled,
            Boolean maintenanceMode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minDeposit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyMaxWithdrawal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal withdrawalFee,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal autoAuditMaxAmount,
            Integer withdrawPrecision,
            int confirmations,
            Integer unlockConfirmations,
            Integer estimatedArrivalMinutes,
            String addressRegex,
            String memoRegex,
            String explorerTxUrlTemplate,
            String specialTips,
            long revision,
            String reason
    ) {
        public NetworkRequest {
            tokenStandard = (tokenStandard == null || tokenStandard.isBlank())
                    ? (nativeAsset ? "NATIVE" : "TOKEN")
                    : tokenStandard.trim();
            enabled = enabled == null ? Boolean.TRUE : enabled;
            isDefault = Boolean.TRUE.equals(isDefault);
            memoRequired = Boolean.TRUE.equals(memoRequired);
            internalWithdrawEnabled = internalWithdrawEnabled == null ? Boolean.TRUE : internalWithdrawEnabled;
            autoWithdrawEnabled = autoWithdrawEnabled == null ? Boolean.TRUE : autoWithdrawEnabled;
            maintenanceMode = Boolean.TRUE.equals(maintenanceMode);
            withdrawPrecision = withdrawPrecision == null ? Math.max(chainDecimals, 0) : withdrawPrecision;
            unlockConfirmations = unlockConfirmations == null ? Math.max(1, confirmations) : unlockConfirmations;
            estimatedArrivalMinutes = estimatedArrivalMinutes == null ? 3 : estimatedArrivalMinutes;
            addressRegex = addressRegex == null ? "" : addressRegex.trim();
            memoRegex = memoRegex == null ? "" : memoRegex.trim();
            explorerTxUrlTemplate = explorerTxUrlTemplate == null ? "" : explorerTxUrlTemplate.trim();
            specialTips = specialTips == null ? "" : specialTips.trim();
        }

        public NetworkRequest(Integer networkId, String networkCode, String displayName,
                              String contractAddress, boolean nativeAsset, int chainDecimals,
                              boolean depositEnabled, boolean withdrawalEnabled,
                              BigDecimal minDeposit, BigDecimal minWithdrawal, BigDecimal withdrawalFee,
                              int confirmations, long revision, String reason) {
            this(networkId, networkCode, displayName, nativeAsset ? "NATIVE" : "TOKEN",
                    contractAddress, nativeAsset, chainDecimals, true, false,
                    depositEnabled, withdrawalEnabled, false, true, true, false,
                    minDeposit, minWithdrawal, null, null, withdrawalFee, null,
                    Math.max(chainDecimals, 0), confirmations, confirmations, 3,
                    "", "", "", "", revision, reason);
        }
    }
}
