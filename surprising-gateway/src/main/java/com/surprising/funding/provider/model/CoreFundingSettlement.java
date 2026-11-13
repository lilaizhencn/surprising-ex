package com.surprising.funding.provider.model;

import com.surprising.funding.api.model.FundingRateResponse;

/** 首次冻结的结算身份与费率，驱动同一确定性分页命令链。 */
public record CoreFundingSettlement(long settlementId, FundingRateResponse rate) { }
