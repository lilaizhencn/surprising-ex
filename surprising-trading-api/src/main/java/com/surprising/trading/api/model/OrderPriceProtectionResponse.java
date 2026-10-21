package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;

/** 用户端预估市价单资金预留时使用的当前产品线保护参数；最终准入由 Core 裁决。 */
public record OrderPriceProtectionResponse(ProductLine productLine, long marketMaxSlippagePpm) { }
