package com.surprising.asset.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 币种是账户资产；Network 只描述同一币种的一个充提入口。 */
public final class AssetConfiguration {
    private AssetConfiguration() {}

    public record Asset(int assetId, String asset, String displayName, String logoUrl, long scaleUnits,
                        boolean listed, boolean tradingEnabled, long revision, Instant updatedAt) {}

    public record AssetRequest(Integer assetId, String asset, String displayName, String logoUrl,
                               long scaleUnits, boolean listed, boolean tradingEnabled, long revision,
                               String reason) {}

    public record Network(int networkId, int assetId, String networkCode, String displayName,
                          String contractAddress, boolean nativeAsset, int chainDecimals,
                          boolean depositEnabled, boolean withdrawalEnabled, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal minDeposit,
                          @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal minWithdrawal, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal withdrawalFee, int confirmations,
                          long revision, Instant updatedAt) {}

    public record NetworkRequest(Integer networkId, String networkCode, String displayName,
                                 String contractAddress, boolean nativeAsset, int chainDecimals,
                                 boolean depositEnabled, boolean withdrawalEnabled, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal minDeposit,
                                 @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal minWithdrawal, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal withdrawalFee, int confirmations,
                                 long revision, String reason) {}
}
