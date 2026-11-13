package com.surprising.funding.provider.model;

import com.surprising.trading.api.utils.AdminCursorPage;

/** Controller已绑定的原生分页条件；SQL列名仅由各Repository选择。 */
public record FundingPageQuery(int limit, boolean descending, AdminCursorPage.Cursor cursor) {}
